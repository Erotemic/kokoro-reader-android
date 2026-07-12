package com.crall.kokororeader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;


import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground owner for speech generation and playback.
 *
 * Activity recreation is deliberately irrelevant to this lifecycle. The service owns the active
 * document, immutable Kokoro settings, network cancellation, MediaPlayer, media session, and queue.
 */
public class PlaybackService extends Service {
    static final String ACTION_STATE_CHANGED = "com.crall.kokororeader.PLAYBACK_STATE_CHANGED";
    static final String ACTION_PLAY = "com.crall.kokororeader.action.PLAY";
    static final String ACTION_TOGGLE = "com.crall.kokororeader.action.TOGGLE";
    static final String ACTION_NEXT = "com.crall.kokororeader.action.NEXT";
    static final String ACTION_SEEK = "com.crall.kokororeader.action.SEEK";
    static final String ACTION_STOP = "com.crall.kokororeader.action.STOP";
    static final String ACTION_CLEAR_HISTORY = "com.crall.kokororeader.action.CLEAR_HISTORY";
    static final String ACTION_CLEAR_CACHE = "com.crall.kokororeader.action.CLEAR_CACHE";
    static final String ACTION_SET_VOLUME = "com.crall.kokororeader.action.SET_VOLUME";
    private static final String EXTRA_PAGE_INDEX = "pageIndex";
    private static final String EXTRA_PAGE_OFFSET_UNITS = "pageOffsetUnits";
    private static final String EXTRA_VOLUME = "volume";
    private static final String PREFS = "kokoro_reader_prefs";
    static final String CHANNEL_ID = "kokoro_reader_playback";
    private static final int NOTIFICATION_ID = 18041;
    private static final int DEFAULT_AUTO_NEXT_DELAY_MS = 650;
    private static final int MAX_AUTO_NEXT_DELAY_MS = 5000;

    public static final class Snapshot {
        public final String documentId;
        public final int pageIndex;
        public final int pageCount;
        public final boolean queueActive;
        public final boolean generationActive;
        public final boolean prepared;
        public final boolean playing;
        public final int positionMs;
        public final int durationMs;
        public final long bytesRead;
        public final long bytesExpected;
        public final long generationStartedAt;
        public final String status;

        Snapshot(
                String documentId,
                int pageIndex,
                int pageCount,
                boolean queueActive,
                boolean generationActive,
                boolean prepared,
                boolean playing,
                int positionMs,
                int durationMs,
                long bytesRead,
                long bytesExpected,
                long generationStartedAt,
                String status) {
            this.documentId = documentId == null ? "" : documentId;
            this.pageIndex = pageIndex;
            this.pageCount = pageCount;
            this.queueActive = queueActive;
            this.generationActive = generationActive;
            this.prepared = prepared;
            this.playing = playing;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.bytesRead = bytesRead;
            this.bytesExpected = bytesExpected;
            this.generationStartedAt = generationStartedAt;
            this.status = status == null ? "Playback: idle" : status;
        }

        static Snapshot idle() {
            return new Snapshot("", -1, 0, false, false, false, false, 0, 0, 0L, -1L, 0L, "Playback: idle");
        }

        public boolean isActive() {
            return queueActive;
        }
    }

    private static volatile Snapshot latestSnapshot = Snapshot.idle();
    private static volatile boolean startRequested;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final ArrayList<String> pages = new ArrayList<>();
    private final Runnable positionTicker = new Runnable() {
        @Override
        public void run() {
            publishSnapshot(false);
            if (generationActive || isPlayerPlaying()) {
                mainHandler.postDelayed(this, 500L);
            }
        }
    };
    private final AudioManager.OnAudioFocusChangeListener focusChangeListener =
            focusChange -> mainHandler.post(() -> handleAudioFocusChange(focusChange));
    private final BroadcastReceiver becomingNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction()) && isPlayerPlaying()) {
                pausePlayer(false, "Paused because the audio output changed.");
            }
        }
    };

    private SharedPreferences prefs;
    private MediaPlayer player;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private MediaSession mediaSession;
    private KokoroAudioRepository.Cancellation generationScope = new KokoroAudioRepository.Cancellation();
    private TtsConfig ttsConfig;
    private boolean hasAudioFocus;
    private boolean resumeAfterFocusGain;
    private boolean userPaused;
    private boolean foreground;
    private boolean generationActive;
    private boolean prepared;
    private int currentPage = -1;
    private int pendingSeekPage = -1;
    private int pendingSeekOffsetUnits = -1;
    private long bytesRead;
    private long bytesExpected = -1L;
    private long generationStartedAt;
    private long generationToken;
    private String documentId = "";
    private String sessionId = "";
    private String status = "Playback: idle";
    private Runnable pendingAutoAdvance;
    private int latestStartId;

    public static Snapshot getSnapshot() {
        return latestSnapshot;
    }

    static void resetStaticStateForTests() {
        latestSnapshot = Snapshot.idle();
        startRequested = false;
    }

    public static void playDocument(Context context, int pageIndex, int pageOffsetUnits) {
        startRequested = true;
        Intent intent = serviceIntent(context, ACTION_PLAY);
        intent.putExtra(EXTRA_PAGE_INDEX, pageIndex);
        intent.putExtra(EXTRA_PAGE_OFFSET_UNITS, pageOffsetUnits);
        startForegroundCommand(context, intent);
    }

    public static void toggle(Context context) {
        if (latestSnapshot.isActive()) {
            context.startService(serviceIntent(context, ACTION_TOGGLE));
        }
    }

    public static void seek(Context context, int pageIndex, int pageOffsetUnits) {
        startRequested = true;
        Intent intent = serviceIntent(context, ACTION_SEEK);
        intent.putExtra(EXTRA_PAGE_INDEX, pageIndex);
        intent.putExtra(EXTRA_PAGE_OFFSET_UNITS, pageOffsetUnits);
        startForegroundCommand(context, intent);
    }

    public static void stop(Context context) {
        if (latestSnapshot.isActive() || startRequested) {
            startRequested = false;
            context.startService(serviceIntent(context, ACTION_STOP));
        }
    }

    public static void clearHistory(Context context) {
        startRequested = false;
        context.startService(serviceIntent(context, ACTION_CLEAR_HISTORY));
    }

    public static void clearAudioCache(Context context) {
        startRequested = false;
        context.startService(serviceIntent(context, ACTION_CLEAR_CACHE));
    }

    public static void setVolume(Context context, float volume) {
        if (!latestSnapshot.isActive()) {
            return;
        }
        Intent intent = serviceIntent(context, ACTION_SET_VOLUME);
        intent.putExtra(EXTRA_VOLUME, Math.max(0f, Math.min(1f, volume)));
        context.startService(intent);
    }

    private static Intent serviceIntent(Context context, String action) {
        return new Intent(context, PlaybackService.class).setAction(action);
    }

    private static void startForegroundCommand(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        createNotificationChannel();
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KokoroReader:Playback");
            wakeLock.setReferenceCounted(false);
        }
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createMediaSession();
        IntentFilter noisyFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(becomingNoisyReceiver, noisyFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(becomingNoisyReceiver, noisyFilter);
        }
        publishSnapshot(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (intent == null || intent.getAction() == null) {
            // Playback is never resurrected implicitly after process death.
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopPlayback("Playback stopped.", true);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_CLEAR_HISTORY.equals(action)) {
            clearHistoryStorage(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_CLEAR_CACHE.equals(action)) {
            clearAudioCache(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(action)) {
            togglePlayback();
            stopIfIdle(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_NEXT.equals(action)) {
            skipToNextPage();
            stopIfIdle(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_SET_VOLUME.equals(action)) {
            applyVolume(intent.getFloatExtra(EXTRA_VOLUME, getPlaybackVolume()));
            publishSnapshot(true);
            stopIfIdle(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_SEEK.equals(action)) {
            loadDocumentAndPlay(
                    intent.getIntExtra(EXTRA_PAGE_INDEX, 0),
                    intent.getIntExtra(EXTRA_PAGE_OFFSET_UNITS, 0));
            return START_NOT_STICKY;
        }
        if (ACTION_PLAY.equals(action)) {
            loadDocumentAndPlay(
                    intent.getIntExtra(EXTRA_PAGE_INDEX, 0),
                    intent.getIntExtra(EXTRA_PAGE_OFFSET_UNITS, -1));
            return START_NOT_STICKY;
        }
        stopSelfResult(startId);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(becomingNoisyReceiver);
        } catch (Exception ignored) {
        }
        stopPlayback("Playback stopped.", true);
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    private void stopIfIdle(int startId) {
        if (!isQueueActive() && !startRequested) {
            stopSelfResult(startId);
        }
    }

    private void loadDocumentAndPlay(int pageIndex, int offsetUnits) {
        startRequested = true;
        ensureForeground("Preparing speech...");
        try {
            PlaybackDocumentStore.Document document = PlaybackDocumentStore.read(this);
            boolean changed = !document.id.equals(documentId);
            cancelGenerationWork();
            clearPendingAutoAdvance();
            releasePlayer();
            if (changed) {
                pages.clear();
                pages.addAll(document.pages);
                documentId = document.id;
                sessionId = document.sessionId;
            }
            ttsConfig = document.ttsConfig;
            if (pages.isEmpty()) {
                stopPlayback("No text is available for playback.", true);
                stopSelfResult(latestStartId);
                return;
            }
            currentPage = clampPage(pageIndex);
            pendingSeekPage = currentPage;
            pendingSeekOffsetUnits = offsetUnits;
            prefs.edit().putInt("lastPage", currentPage).apply();
            userPaused = false;
            generateAndPlayPage(currentPage);
        } catch (Exception ex) {
            fail("Could not start background playback: " + ex.getMessage());
        }
    }

    private void generateAndPlayPage(int pageIndex) {
        if (pages.isEmpty() || ttsConfig == null) {
            fail("No text is available for playback.");
            return;
        }
        beginGenerationWork();
        currentPage = clampPage(pageIndex);
        prefs.edit().putInt("lastPage", currentPage).apply();
        clearPendingAutoAdvance();
        String text = pages.get(currentPage);
        File playable = KokoroAudioRepository.findPlayable(
                this, ttsConfig, getHistoryLimit(), sessionId, currentPage, text);
        if (playable != null) {
            status = "Using saved audio for page " + (currentPage + 1) + ".";
            publishSnapshot(true);
            playAudio(playable, currentPage);
            prefetchAhead(currentPage, generationScope);
            return;
        }

        final int requestedPage = currentPage;
        final String requestedDocument = documentId;
        final String requestedSession = sessionId;
        final String requestedText = text;
        final TtsConfig requestedConfig = ttsConfig;
        final long token = generationToken;
        final KokoroAudioRepository.Cancellation scope = generationScope;
        generationActive = true;
        prepared = false;
        bytesRead = 0L;
        bytesExpected = -1L;
        generationStartedAt = System.currentTimeMillis();
        status = "Generating page " + (requestedPage + 1) + " of " + pages.size() + "...";
        acquireWakeLock();
        ensureForeground(status);
        publishSnapshot(true);
        startPositionTicker();

        executor.submit(() -> {
            try {
                File output = KokoroAudioRepository.requestSpeech(
                        this,
                        requestedConfig,
                        getHistoryLimit(),
                        requestedSession,
                        requestedPage,
                        requestedText,
                        scope,
                        (received, expected) -> reportDownloadProgress(
                                requestedPage, received, expected, token, requestedDocument, scope));
                mainHandler.post(() -> {
                    if (!isCurrentRequest(token, requestedDocument, requestedPage, scope)) {
                        return;
                    }
                    generationActive = false;
                    status = "Generated page " + (requestedPage + 1) + ". Playing.";
                    publishSnapshot(true);
                    playAudio(output, requestedPage);
                    prefetchAhead(requestedPage, scope);
                    updateHistoryProgressAsync();
                });
            } catch (InterruptedException ignored) {
                // Cancellation is an expected lifecycle event.
            } catch (Exception ex) {
                mainHandler.post(() -> {
                    if (!isCurrentRequest(token, requestedDocument, requestedPage, scope)) {
                        return;
                    }
                    generationActive = false;
                    fail("Generation failed: " + ex.getMessage());
                });
            }
        });
    }

    private boolean isCurrentRequest(
            long token,
            String requestedDocument,
            int requestedPage,
            KokoroAudioRepository.Cancellation scope) {
        return !scope.isCancelled()
                && token == generationToken
                && requestedDocument.equals(documentId)
                && requestedPage == currentPage;
    }

    private void playAudio(File file, int pageIndex) {
        releasePlayer();
        acquireWakeLock();
        try {
            MediaPlayer next = new MediaPlayer();
            AudioAttributes attributes = speechAudioAttributes();
            next.setAudioAttributes(attributes);
            next.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            next.setDataSource(file.getAbsolutePath());
            next.setOnPreparedListener(mediaPlayer -> {
                if (mediaPlayer != player) {
                    return;
                }
                prepared = true;
                generationActive = false;
                currentPage = pageIndex;
                applyPlaybackRate(mediaPlayer);
                applyVolume(getPlaybackVolume());
                if (pendingSeekPage == pageIndex && pendingSeekOffsetUnits >= 0) {
                    try {
                        mediaPlayer.seekTo(msForPageOffsetUnits(
                                pageIndex, pendingSeekOffsetUnits, mediaPlayer.getDuration()));
                    } catch (Exception ignored) {
                    }
                }
                pendingSeekPage = -1;
                pendingSeekOffsetUnits = -1;
                startPreparedPlayer();
            });
            next.setOnCompletionListener(mediaPlayer -> {
                if (mediaPlayer != player) {
                    return;
                }
                int completedPage = pageIndex;
                releasePlayer();
                if (PlaybackPolicy.shouldAutoAdvance(
                        prefBool("autoNext", true), completedPage, pages.size())) {
                    scheduleAutoAdvance(completedPage);
                } else {
                    finishPlayback("Finished page " + (completedPage + 1) + ".");
                }
            });
            next.setOnErrorListener((mediaPlayer, what, extra) -> {
                if (mediaPlayer == player) {
                    fail("Playback error: what=" + what + " extra=" + extra + ".");
                }
                return true;
            });
            player = next;
            next.prepareAsync();
        } catch (Exception ex) {
            fail("Could not play audio: " + ex.getMessage());
        }
    }

    private void startPreparedPlayer() {
        if (player == null || !prepared || userPaused) {
            releaseWakeLock();
            return;
        }
        if (!requestAudioFocus()) {
            releaseWakeLock();
            if (resumeAfterFocusGain) {
                status = "Ready, waiting for audio focus.";
            } else {
                userPaused = true;
                status = "Audio focus is unavailable. Tap Play to retry.";
            }
            ensureForeground(status);
            publishSnapshot(true);
            return;
        }
        try {
            resumeAfterFocusGain = false;
            acquireWakeLock();
            applyPlaybackRate(player);
            applyVolume(getPlaybackVolume());
            player.start();
            status = "Playing page " + (currentPage + 1) + " of " + pages.size() + ".";
            ensureForeground(status);
            publishSnapshot(true);
            startPositionTicker();
        } catch (Exception ex) {
            fail("Could not start playback: " + ex.getMessage());
        }
    }

    private void scheduleAutoAdvance(int completedPage) {
        clearPendingAutoAdvance();
        // Refresh the bounded wake lock so screen-off playback cannot sleep in the gap
        // between MediaPlayer completion and the delayed next-page generation command.
        releaseWakeLock();
        acquireWakeLock();
        int nextPage = completedPage + 1;
        int delay = getAutoNextDelayMs();
        status = "Finished page " + (completedPage + 1) + ". Starting page " + (nextPage + 1)
                + (delay > 0 ? " after a short pause." : ".");
        publishSnapshot(true);
        pendingAutoAdvance = () -> {
            pendingAutoAdvance = null;
            currentPage = nextPage;
            prefs.edit().putInt("lastPage", currentPage).apply();
            updateHistoryProgressAsync();
            generateAndPlayPage(currentPage);
        };
        if (delay > 0) {
            mainHandler.postDelayed(pendingAutoAdvance, delay);
        } else {
            mainHandler.post(pendingAutoAdvance);
        }
    }

    private void skipToNextPage() {
        if (currentPage + 1 >= pages.size()) {
            status = "Already at the final page.";
            publishSnapshot(true);
            return;
        }
        skipToPage(currentPage + 1);
    }

    private void skipToPage(int pageIndex) {
        clearPendingAutoAdvance();
        releasePlayer();
        currentPage = clampPage(pageIndex);
        userPaused = false;
        prefs.edit().putInt("lastPage", currentPage).apply();
        updateHistoryProgressAsync();
        generateAndPlayPage(currentPage);
    }

    private void togglePlayback() {
        if (pendingAutoAdvance != null) {
            Runnable advanceNow = pendingAutoAdvance;
            clearPendingAutoAdvance();
            advanceNow.run();
            return;
        }
        if (player == null || !prepared) {
            if (generationActive) {
                status = "Audio is still being generated.";
                publishSnapshot(true);
                return;
            }
            if (!documentId.isEmpty()) {
                generateAndPlayPage(Math.max(0, currentPage));
            }
            return;
        }
        if (resumeAfterFocusGain && !hasAudioFocus && !userPaused) {
            pausePlayer(false, "Playback paused.");
            return;
        }
        if (isPlayerPlaying()) {
            pausePlayer(false, "Paused page " + (currentPage + 1) + ".");
        } else {
            userPaused = false;
            startPreparedPlayer();
        }
    }

    private void pausePlayer(boolean resumeOnFocusGain, String message) {
        if (player != null && prepared) {
            try {
                if (player.isPlaying()) {
                    player.pause();
                }
            } catch (Exception ignored) {
            }
        }
        userPaused = !resumeOnFocusGain;
        resumeAfterFocusGain = resumeOnFocusGain;
        if (!resumeOnFocusGain) {
            abandonAudioFocus();
        }
        releaseWakeLock();
        status = message;
        publishSnapshot(true);
    }

    private void finishPlayback(String message) {
        String completedDocumentId = documentId;
        startRequested = false;
        cancelGenerationWork();
        clearPendingAutoAdvance();
        status = message;
        releasePlayer();
        abandonAudioFocus();
        releaseWakeLock();
        clearDocumentState();
        PlaybackDocumentStore.clearIfMatches(this, completedDocumentId);
        publishSnapshot(true);
        removeForeground();
        stopSelfResult(latestStartId);
    }

    private void stopPlayback(String message, boolean clearStoredDocument) {
        String stoppedDocumentId = documentId;
        startRequested = false;
        cancelGenerationWork();
        clearPendingAutoAdvance();
        status = message;
        releasePlayer();
        abandonAudioFocus();
        releaseWakeLock();
        clearDocumentState();
        if (clearStoredDocument) {
            PlaybackDocumentStore.clearIfMatches(this, stoppedDocumentId);
        }
        publishSnapshot(true);
        removeForeground();
    }

    private void clearHistoryStorage(int startId) {
        stopPlayback("Playback stopped.", true);
        File root = new File(getFilesDir(), "kokoro_history");
        int deleted = AtomicFileStore.deleteTree(root);
        status = "Cleared speech history storage (" + deleted + " file/folder entries removed).";
        publishSnapshot(true);
        stopSelfResult(startId);
    }

    private void clearAudioCache(int startId) {
        stopPlayback("Playback stopped.", true);
        int deleted = AtomicFileStore.deleteTree(KokoroAudioRepository.cacheDirectory(this));
        status = "Cleared " + deleted + " transient cached audio file/folder entries.";
        publishSnapshot(true);
        stopSelfResult(startId);
    }

    private void fail(String message) {
        String failedDocumentId = documentId;
        startRequested = false;
        cancelGenerationWork();
        clearPendingAutoAdvance();
        status = message;
        releasePlayer();
        abandonAudioFocus();
        releaseWakeLock();
        clearDocumentState();
        PlaybackDocumentStore.clearIfMatches(this, failedDocumentId);
        publishSnapshot(true);
        removeForeground();
        stopSelfResult(latestStartId);
    }

    private void clearDocumentState() {
        mainHandler.removeCallbacks(positionTicker);
        generationActive = false;
        prepared = false;
        bytesRead = 0L;
        bytesExpected = -1L;
        generationStartedAt = 0L;
        documentId = "";
        sessionId = "";
        ttsConfig = null;
        pages.clear();
        currentPage = -1;
        pendingSeekPage = -1;
        pendingSeekOffsetUnits = -1;
        userPaused = false;
        resumeAfterFocusGain = false;
    }

    private void beginGenerationWork() {
        cancelGenerationWork();
        generationScope = new KokoroAudioRepository.Cancellation();
        generationToken++;
        bytesRead = 0L;
        bytesExpected = -1L;
        generationStartedAt = 0L;
    }

    private void cancelGenerationWork() {
        generationToken++;
        generationScope.cancel();
        generationActive = false;
    }

    private void reportDownloadProgress(
            int pageIndex,
            long received,
            long expected,
            long token,
            String requestedDocument,
            KokoroAudioRepository.Cancellation scope) {
        mainHandler.post(() -> {
            if (!isCurrentRequest(token, requestedDocument, pageIndex, scope) || !generationActive) {
                return;
            }
            bytesRead = received;
            bytesExpected = expected;
            if (expected > 0L) {
                int percent = (int) Math.max(0L, Math.min(99L, received * 100L / Math.max(1L, expected)));
                status = "Receiving Kokoro audio for page " + (pageIndex + 1) + ": " + percent + "%";
            } else {
                status = "Receiving Kokoro audio for page " + (pageIndex + 1) + ": " + formatBytes(received);
            }
            publishSnapshot(true);
        });
    }

    private void prefetchAhead(int fromPage, KokoroAudioRepository.Cancellation scope) {
        int count = getPrefetchPages();
        if (count <= 0 || fromPage + 1 >= pages.size() || ttsConfig == null) {
            return;
        }
        final int start = fromPage + 1;
        final ArrayList<String> requestedPages = new ArrayList<>(pages);
        final int end = Math.min(requestedPages.size(), start + count);
        final String requestedDocument = documentId;
        final String requestedSession = sessionId;
        final TtsConfig requestedConfig = ttsConfig;
        executor.submit(() -> {
            for (int index = start; index < end; index++) {
                if (scope.isCancelled() || !requestedDocument.equals(documentId)) {
                    return;
                }
                String pageText = requestedPages.get(index);
                try {
                    if (KokoroAudioRepository.findPlayable(
                            this, requestedConfig, getHistoryLimit(), requestedSession, index, pageText) == null) {
                        KokoroAudioRepository.requestSpeech(
                                this,
                                requestedConfig,
                                getHistoryLimit(),
                                requestedSession,
                                index,
                                pageText,
                                scope,
                                null);
                    }
                } catch (InterruptedException ignored) {
                    return;
                } catch (Exception ignored) {
                    return;
                }
            }
            mainHandler.post(() -> {
                if (!scope.isCancelled() && requestedDocument.equals(documentId)) {
                    publishSnapshot(true);
                    updateHistoryProgressAsync();
                }
            });
        });
    }

    private void updateHistoryProgressAsync() {
        final String requestedSession = sessionId;
        final String requestedDocument = documentId;
        final int requestedPage = currentPage;
        final ArrayList<String> requestedPages = new ArrayList<>(pages);
        final TtsConfig requestedConfig = ttsConfig;
        final KokoroAudioRepository.Cancellation scope = generationScope;
        if (requestedSession.isEmpty() || requestedConfig == null || getHistoryLimit() <= 0) {
            return;
        }
        executor.submit(() -> {
            try {
                int audioCount = 0;
                for (int index = 0; index < requestedPages.size(); index++) {
                    scope.throwIfCancelled();
                    if (KokoroAudioRepository.findPlayable(
                            this,
                            requestedConfig,
                            getHistoryLimit(),
                            requestedSession,
                            index,
                            requestedPages.get(index)) != null) {
                        audioCount++;
                    }
                }
                final int finalAudioCount = audioCount;
                File jsonFile = new File(
                        new File(new File(getFilesDir(), "kokoro_history"), safeFileName(requestedSession)),
                        "session.json");
                synchronized (AtomicFileStore.class) {
                    scope.throwIfCancelled();
                    if (!jsonFile.isFile()) {
                        return;
                    }
                    AtomicFileStore.mutateJson(jsonFile, metadata -> {
                        metadata.put("currentPage", requestedPage);
                        metadata.put("updatedAt", System.currentTimeMillis());
                        metadata.put("audioCount", finalAudioCount);
                    });
                }
                if (!scope.isCancelled() && requestedDocument.equals(documentId)) {
                    mainHandler.post(() -> publishSnapshot(true));
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void ensureForeground(String text) {
        Notification notification = buildNotification(text);
        if (!foreground) {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foreground = true;
        } else {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, notification);
            }
        }
    }

    private void removeForeground() {
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 10, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent toggleIntent = PendingIntent.getService(
                this, 11, serviceIntent(this, ACTION_TOGGLE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent nextIntent = PendingIntent.getService(
                this, 12, serviceIntent(this, ACTION_NEXT), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopIntent = PendingIntent.getService(
                this, 13, serviceIntent(this, ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean playing = isPlayerPlaying();
        boolean playRequestActive = playing || (resumeAfterFocusGain && !userPaused);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification.MediaStyle mediaStyle = new Notification.MediaStyle();
        if (mediaSession != null) {
            mediaStyle.setMediaSession(mediaSession.getSessionToken());
        }
        mediaStyle.setShowActionsInCompactView(0, 1, 2);
        builder.setSmallIcon(R.drawable.ic_stat_kokoro)
                .setContentTitle(pages.isEmpty() || currentPage < 0
                        ? "Kokoro Reader"
                        : "Kokoro Reader • page " + (currentPage + 1) + "/" + pages.size())
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(isQueueActive())
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(mediaStyle)
                .addAction(new Notification.Action.Builder(
                        playRequestActive ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playRequestActive ? "Pause" : pendingAutoAdvance != null ? "Next" : "Play",
                        toggleIntent).build())
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_stat_next,
                        "Next",
                        nextIntent).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Stop",
                        stopIntent).build());
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Speech playback",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps Kokoro Reader speaking while the screen is off or another app is open.");
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    private void createMediaSession() {
        mediaSession = new MediaSession(this, "KokoroReaderPlayback");
        mediaSession.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mediaSession.setSessionActivity(PendingIntent.getActivity(
                this,
                14,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                mainHandler.post(() -> {
                    userPaused = false;
                    if (player != null && prepared) {
                        startPreparedPlayer();
                    } else if (!documentId.isEmpty()) {
                        generateAndPlayPage(Math.max(0, currentPage));
                    }
                });
            }

            @Override
            public void onPause() {
                mainHandler.post(() -> pausePlayer(false, "Playback paused."));
            }

            @Override
            public void onStop() {
                mainHandler.post(() -> {
                    stopPlayback("Playback stopped.", true);
                    stopSelfResult(latestStartId);
                });
            }

            @Override
            public void onSkipToNext() {
                mainHandler.post(PlaybackService.this::skipToNextPage);
            }

            @Override
            public void onSkipToPrevious() {
                mainHandler.post(() -> {
                    if (currentPage > 0) {
                        skipToPage(currentPage - 1);
                    }
                });
            }
        });
        mediaSession.setActive(true);
    }

    private void publishSnapshot(boolean broadcast) {
        int position = 0;
        int duration = 0;
        boolean playing = false;
        MediaPlayer current = player;
        if (current != null && prepared) {
            try {
                position = current.getCurrentPosition();
                duration = current.getDuration();
                playing = current.isPlaying();
            } catch (Exception ignored) {
            }
        }
        latestSnapshot = new Snapshot(
                documentId,
                currentPage,
                pages.size(),
                isQueueActive(),
                generationActive,
                prepared,
                playing,
                position,
                duration,
                bytesRead,
                bytesExpected,
                generationStartedAt,
                status);
        updateMediaSession(position, duration, playing);
        if (broadcast) {
            Intent changed = new Intent(ACTION_STATE_CHANGED).setPackage(getPackageName());
            sendBroadcast(changed);
            if (foreground) {
                ensureForeground(status);
            }
        }
    }

    private void updateMediaSession(int position, int duration, boolean playing) {
        if (mediaSession == null) {
            return;
        }
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        int state;
        if (playing) {
            state = PlaybackState.STATE_PLAYING;
        } else if (generationActive) {
            state = PlaybackState.STATE_BUFFERING;
        } else if (prepared || pendingAutoAdvance != null) {
            state = PlaybackState.STATE_PAUSED;
        } else {
            state = PlaybackState.STATE_STOPPED;
        }
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, position, playing ? getPlaybackRate() : 0f)
                .build());
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Kokoro Reader")
                .putString(
                        MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                        currentPage >= 0 && !pages.isEmpty()
                                ? "Page " + (currentPage + 1) + " of " + pages.size()
                                : status);
        if (duration > 0) {
            metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, duration);
        }
        mediaSession.setMetadata(metadata.build());
    }

    private void startPositionTicker() {
        mainHandler.removeCallbacks(positionTicker);
        mainHandler.post(positionTicker);
    }

    private void clearPendingAutoAdvance() {
        if (pendingAutoAdvance != null) {
            mainHandler.removeCallbacks(pendingAutoAdvance);
            pendingAutoAdvance = null;
        }
    }

    private void releasePlayer() {
        MediaPlayer old = player;
        player = null;
        prepared = false;
        if (old != null) {
            try {
                old.stop();
            } catch (Exception ignored) {
            }
            try {
                old.release();
            } catch (Exception ignored) {
            }
        }
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) {
            return true;
        }
        if (hasAudioFocus) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            if (audioFocusRequest == null) {
                audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(speechAudioAttributes())
                        .setAcceptsDelayedFocusGain(true)
                        .setWillPauseWhenDucked(true)
                        .setOnAudioFocusChangeListener(focusChangeListener)
                        .build();
            }
            int result = audioManager.requestAudioFocus(audioFocusRequest);
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            resumeAfterFocusGain = result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED;
            return hasAudioFocus;
        }
        int result = audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return hasAudioFocus;
    }

    private void handleAudioFocusChange(int focusChange) {
        PlaybackPolicy.AudioFocusDirective directive =
                PlaybackPolicy.classifyAudioFocusChange(focusChange);
        if (directive == PlaybackPolicy.AudioFocusDirective.GAIN) {
            hasAudioFocus = true;
            if (resumeAfterFocusGain && !userPaused && player != null && prepared) {
                startPreparedPlayer();
            }
            return;
        }
        if (directive == PlaybackPolicy.AudioFocusDirective.IGNORE) {
            return;
        }
        hasAudioFocus = false;
        if (directive == PlaybackPolicy.AudioFocusDirective.PAUSE_AND_RESUME) {
            if (isPlayerPlaying()) {
                pausePlayer(true, "Paused while another app uses audio.");
            }
        } else {
            if (isPlayerPlaying()) {
                pausePlayer(false, "Paused because another app took audio focus.");
            }
            resumeAfterFocusGain = false;
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(focusChangeListener);
        }
        hasAudioFocus = false;
        resumeAfterFocusGain = false;
        audioFocusRequest = null;
    }

    private AudioAttributes speechAudioAttributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(6L * 60L * 60L * 1000L);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private boolean isPlayerPlaying() {
        if (player == null || !prepared) {
            return false;
        }
        try {
            return player.isPlaying();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isQueueActive() {
        return !documentId.isEmpty()
                && (generationActive || player != null || pendingAutoAdvance != null);
    }

    private void applyPlaybackRate(MediaPlayer mediaPlayer) {
        try {
            mediaPlayer.setPlaybackParams(mediaPlayer.getPlaybackParams().setSpeed(getPlaybackRate()));
        } catch (Exception ignored) {
        }
    }

    private void applyVolume(float volume) {
        if (player != null) {
            try {
                player.setVolume(volume, volume);
            } catch (Exception ignored) {
            }
        }
    }

    private int clampPage(int pageIndex) {
        return Math.max(0, Math.min(pageIndex, Math.max(0, pages.size() - 1)));
    }

    private int msForPageOffsetUnits(int pageIndex, int offsetUnits, int durationMs) {
        if (pageIndex < 0 || pageIndex >= pages.size()) {
            return 0;
        }
        return PlaybackPolicy.pageOffsetToMilliseconds(
                offsetUnits, Math.max(1, pages.get(pageIndex).length()), durationMs);
    }

    private float getPlaybackRate() {
        try {
            float value = Float.parseFloat(prefString("playbackRate", "1.0").trim());
            if (!Float.isFinite(value)) {
                return 1.0f;
            }
            return Math.max(0.25f, Math.min(4.0f, value));
        } catch (Exception ignored) {
            return 1.0f;
        }
    }

    private float getPlaybackVolume() {
        try {
            int percent = Math.max(0, Math.min(100, Integer.parseInt(prefString("volumePercent", "100").trim())));
            return percent / 100f;
        } catch (Exception ignored) {
            return 1f;
        }
    }

    private int getPrefetchPages() {
        try {
            return Math.max(0, Math.min(50, Integer.parseInt(prefString("prefetchPages", "5").trim())));
        } catch (Exception ignored) {
            return 5;
        }
    }

    private int getHistoryLimit() {
        try {
            return Math.max(0, Math.min(200, Integer.parseInt(prefString("historyLimit", "20").trim())));
        } catch (Exception ignored) {
            return 20;
        }
    }

    private int getAutoNextDelayMs() {
        try {
            return Math.max(
                    0,
                    Math.min(
                            MAX_AUTO_NEXT_DELAY_MS,
                            Integer.parseInt(prefString(
                                    "autoNextDelayMs",
                                    String.valueOf(DEFAULT_AUTO_NEXT_DELAY_MS)).trim())));
        } catch (Exception ignored) {
            return DEFAULT_AUTO_NEXT_DELAY_MS;
        }
    }

    private String prefString(String key, String fallback) {
        return prefs.getString(key, fallback);
    }

    private boolean prefBool(String key, boolean fallback) {
        return prefs.getBoolean(key, fallback);
    }

    private String safeFileName(String value) {
        String safe = value == null ? "" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return safe.isEmpty() ? "session" : safe;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kib = bytes / 1024.0;
        return kib < 1024.0
                ? String.format(Locale.US, "%.1f KiB", kib)
                : String.format(Locale.US, "%.1f MiB", kib / 1024.0);
    }
}
