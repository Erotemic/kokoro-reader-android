package com.crall.kokororeader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground owner for speech generation and playback.
 *
 * The activity is deliberately not part of this lifecycle. Rotation, Home, app switching,
 * and ordinary activity recreation therefore cannot interrupt the active document queue.
 */
public class PlaybackService extends Service {
    static final String ACTION_STATE_CHANGED = "com.crall.kokororeader.PLAYBACK_STATE_CHANGED";
    private static final String ACTION_PLAY = "com.crall.kokororeader.action.PLAY";
    private static final String ACTION_TOGGLE = "com.crall.kokororeader.action.TOGGLE";
    private static final String ACTION_SEEK = "com.crall.kokororeader.action.SEEK";
    private static final String ACTION_STOP = "com.crall.kokororeader.action.STOP";
    private static final String ACTION_SET_VOLUME = "com.crall.kokororeader.action.SET_VOLUME";
    private static final String EXTRA_PAGE_INDEX = "pageIndex";
    private static final String EXTRA_PAGE_OFFSET_UNITS = "pageOffsetUnits";
    private static final String EXTRA_VOLUME = "volume";
    private static final String PREFS = "kokoro_reader_prefs";
    private static final String CHANNEL_ID = "kokoro_reader_playback";
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
    private final HashSet<String> activeGenerations = new HashSet<>();
    private final ArrayList<String> pages = new ArrayList<>();
    private final Runnable positionTicker = new Runnable() {
        @Override
        public void run() {
            publishSnapshot(false);
            if (generationActive || player != null || pendingAutoAdvance != null) {
                mainHandler.postDelayed(this, 500L);
            }
        }
    };

    private SharedPreferences prefs;
    private MediaPlayer player;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus;
    private boolean foreground;
    private boolean generationActive;
    private boolean prepared;
    private int currentPage = -1;
    private int pendingSeekPage = -1;
    private int pendingSeekOffsetUnits = -1;
    private long bytesRead;
    private long bytesExpected = -1L;
    private long generationStartedAt;
    private volatile long generationToken;
    private volatile String documentId = "";
    private String sessionId = "";
    private String status = "Playback: idle";
    private Runnable pendingAutoAdvance;
    private int latestStartId;

    public static Snapshot getSnapshot() {
        return latestSnapshot;
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
        publishSnapshot(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        String action = intent == null ? ACTION_PLAY : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            startRequested = false;
            stopPlayback("Playback stopped.");
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(action)) {
            togglePlayback();
            return START_STICKY;
        }
        if (ACTION_SET_VOLUME.equals(action)) {
            applyVolume(intent == null ? getPlaybackVolume() : intent.getFloatExtra(EXTRA_VOLUME, getPlaybackVolume()));
            return START_STICKY;
        }
        if (ACTION_SEEK.equals(action)) {
            int pageIndex = intent.getIntExtra(EXTRA_PAGE_INDEX, 0);
            int offsetUnits = intent.getIntExtra(EXTRA_PAGE_OFFSET_UNITS, 0);
            loadDocumentAndPlay(pageIndex, offsetUnits);
            return START_STICKY;
        }
        int pageIndex = intent == null ? prefs.getInt("lastPage", 0) : intent.getIntExtra(EXTRA_PAGE_INDEX, 0);
        int offsetUnits = intent == null ? -1 : intent.getIntExtra(EXTRA_PAGE_OFFSET_UNITS, -1);
        loadDocumentAndPlay(pageIndex, offsetUnits);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopPlayback("Playback stopped.");
        executor.shutdownNow();
        super.onDestroy();
    }

    private void loadDocumentAndPlay(int pageIndex, int offsetUnits) {
        startRequested = true;
        ensureForeground("Preparing speech...");
        try {
            PlaybackDocumentStore.Document document = PlaybackDocumentStore.read(this);
            boolean changed = !document.id.equals(documentId);
            if (changed) {
                generationToken++;
                clearPendingAutoAdvance();
                releasePlayer();
                pages.clear();
                pages.addAll(document.pages);
                documentId = document.id;
                sessionId = document.sessionId;
            }
            if (pages.isEmpty()) {
                stopPlayback("No text is available for playback.");
                stopSelfResult(latestStartId);
                return;
            }
            currentPage = clampPage(pageIndex);
            pendingSeekPage = currentPage;
            pendingSeekOffsetUnits = offsetUnits;
            prefs.edit().putInt("lastPage", currentPage).apply();
            generateAndPlayPage(currentPage);
        } catch (Exception ex) {
            fail("Could not start background playback: " + ex.getMessage());
        }
    }

    private void generateAndPlayPage(int pageIndex) {
        if (pages.isEmpty()) {
            fail("No text is available for playback.");
            return;
        }
        currentPage = clampPage(pageIndex);
        prefs.edit().putInt("lastPage", currentPage).apply();
        clearPendingAutoAdvance();
        File playable = findPlayableAudioFile(currentPage, pages.get(currentPage));
        if (playable != null) {
            status = "Using saved audio for page " + (currentPage + 1) + ".";
            publishSnapshot(true);
            playAudio(playable, currentPage);
            prefetchAhead(currentPage);
            return;
        }

        final int requestedPage = currentPage;
        final String requestedDocument = documentId;
        final String requestedSession = sessionId;
        final String requestedText = pages.get(requestedPage);
        final long token = ++generationToken;
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
                File output = requestSpeech(requestedText, requestedPage, requestedSession, token, requestedDocument);
                mainHandler.post(() -> {
                    if (token != generationToken || !requestedDocument.equals(documentId) || requestedPage != currentPage) {
                        return;
                    }
                    generationActive = false;
                    status = "Generated page " + (requestedPage + 1) + ". Playing.";
                    publishSnapshot(true);
                    playAudio(output, requestedPage);
                    prefetchAhead(requestedPage);
                });
            } catch (Exception ex) {
                mainHandler.post(() -> {
                    if (token != generationToken || !requestedDocument.equals(documentId)) {
                        return;
                    }
                    generationActive = false;
                    fail("Generation failed: " + ex.getMessage());
                });
            }
        });
    }

    private void playAudio(File file, int pageIndex) {
        releasePlayer();
        requestAudioFocus();
        acquireWakeLock();
        try {
            MediaPlayer next = new MediaPlayer();
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
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
                        mediaPlayer.seekTo(msForPageOffsetUnits(pageIndex, pendingSeekOffsetUnits, mediaPlayer.getDuration()));
                    } catch (Exception ignored) {
                    }
                }
                pendingSeekPage = -1;
                pendingSeekOffsetUnits = -1;
                mediaPlayer.start();
                status = "Playing page " + (pageIndex + 1) + " of " + pages.size() + ".";
                ensureForeground(status);
                publishSnapshot(true);
                startPositionTicker();
            });
            next.setOnCompletionListener(mediaPlayer -> {
                if (mediaPlayer != player) {
                    return;
                }
                prepared = false;
                if (prefBool("autoNext", true) && pageIndex + 1 < pages.size()) {
                    scheduleAutoAdvance(pageIndex);
                } else {
                    finishPlayback("Finished page " + (pageIndex + 1) + ".");
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

    private void scheduleAutoAdvance(int completedPage) {
        clearPendingAutoAdvance();
        int nextPage = completedPage + 1;
        int delay = getAutoNextDelayMs();
        status = "Finished page " + (completedPage + 1) + ". Starting page " + (nextPage + 1) + (delay > 0 ? " after a short pause." : ".");
        publishSnapshot(true);
        pendingAutoAdvance = () -> {
            pendingAutoAdvance = null;
            currentPage = nextPage;
            prefs.edit().putInt("lastPage", currentPage).apply();
            updateHistoryProgress();
            generateAndPlayPage(currentPage);
        };
        if (delay > 0) {
            mainHandler.postDelayed(pendingAutoAdvance, delay);
        } else {
            mainHandler.post(pendingAutoAdvance);
        }
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
            loadDocumentAndPlay(Math.max(0, currentPage), -1);
            return;
        }
        try {
            if (player.isPlaying()) {
                player.pause();
                releaseWakeLock();
                status = "Paused page " + (currentPage + 1) + ".";
                publishSnapshot(true);
                updateNotification();
            } else {
                requestAudioFocus();
                acquireWakeLock();
                applyPlaybackRate(player);
                applyVolume(getPlaybackVolume());
                player.start();
                status = "Playing page " + (currentPage + 1) + " of " + pages.size() + ".";
                ensureForeground(status);
                publishSnapshot(true);
                startPositionTicker();
            }
        } catch (Exception ex) {
            fail("Could not toggle playback: " + ex.getMessage());
        }
    }

    private void finishPlayback(String message) {
        startRequested = false;
        clearPendingAutoAdvance();
        generationActive = false;
        prepared = false;
        status = message;
        releasePlayer();
        abandonAudioFocus();
        releaseWakeLock();
        publishSnapshot(true);
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        stopSelfResult(latestStartId);
    }

    private void stopPlayback(String message) {
        startRequested = false;
        generationToken++;
        clearPendingAutoAdvance();
        generationActive = false;
        prepared = false;
        bytesRead = 0L;
        bytesExpected = -1L;
        generationStartedAt = 0L;
        status = message;
        releasePlayer();
        abandonAudioFocus();
        releaseWakeLock();
        publishSnapshot(true);
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
    }

    private void fail(String message) {
        startRequested = false;
        status = message;
        generationActive = false;
        prepared = false;
        releasePlayer();
        abandonAudioFocus();
        releaseWakeLock();
        publishSnapshot(true);
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        stopSelfResult(latestStartId);
    }

    private File requestSpeech(String text, int pageIndex, String requestedSessionId, long token, String requestedDocument) throws Exception {
        File existing = findPlayableAudioFile(requestedSessionId, pageIndex, text);
        if (existing != null) {
            return existing;
        }
        File cached = audioFileFor(pageIndex, text);
        String generationKey = cached.getAbsolutePath();
        if (!markGenerationStarted(generationKey)) {
            File waited = waitForGeneratedAudio(requestedSessionId, pageIndex, text, 180000L);
            if (waited != null) {
                return waited;
            }
            throw new IllegalStateException("Timed out waiting for page " + (pageIndex + 1) + ".");
        }
        try {
            File parent = cached.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IllegalStateException("Could not create audio cache directory.");
            }
            HttpURLConnection connection = (HttpURLConnection) new URL(getServerBase() + "/v1/audio/speech").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(180000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", acceptHeaderForFormat(getResponseFormat()));

            JSONObject payload = new JSONObject();
            payload.put("model", getModel());
            payload.put("input", text);
            payload.put("voice", getVoice());
            payload.put("response_format", getResponseFormat());
            payload.put("speed", getTtsSpeed());
            payload.put("stream", prefBool("stream", true));
            String language = getLangCode();
            if (!language.isEmpty()) {
                payload.put("lang_code", language);
            }
            JSONObject normalization = new JSONObject();
            normalization.put("normalize", prefBool("normalize", true));
            normalization.put("unit_normalization", prefBool("unitNorm", false));
            normalization.put("url_normalization", prefBool("urlNorm", true));
            normalization.put("email_normalization", prefBool("emailNorm", true));
            normalization.put("optional_pluralization_normalization", prefBool("pluralNorm", true));
            normalization.put("phone_normalization", prefBool("phoneNorm", true));
            payload.put("normalization_options", normalization);

            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + " from Kokoro: " + readError(connection));
            }

            File tmp = new File(cached.getAbsolutePath() + ".service.tmp");
            long expected = connection.getContentLengthLong();
            boolean reportProgress = token != Long.MIN_VALUE;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(tmp)) {
                byte[] buffer = new byte[8192];
                int count;
                long total = 0L;
                long lastReportedBytes = 0L;
                long lastReportedAt = 0L;
                if (reportProgress) {
                    reportDownloadProgress(pageIndex, total, expected, token, requestedDocument);
                }
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    total += count;
                    long now = System.currentTimeMillis();
                    if (reportProgress && (total - lastReportedBytes >= 65536L || now - lastReportedAt >= 750L)) {
                        lastReportedBytes = total;
                        lastReportedAt = now;
                        reportDownloadProgress(pageIndex, total, expected, token, requestedDocument);
                    }
                }
                if (reportProgress) {
                    reportDownloadProgress(pageIndex, total, total, token, requestedDocument);
                }
            } finally {
                connection.disconnect();
            }
            if (tmp.length() == 0L) {
                tmp.delete();
                throw new IllegalStateException("Kokoro returned an empty audio file.");
            }
            if (cached.exists()) {
                cached.delete();
            }
            if (!tmp.renameTo(cached)) {
                throw new IllegalStateException("Could not move generated audio into cache.");
            }
            persistGeneratedAudio(requestedSessionId, pageIndex, text, cached);
            return cached;
        } finally {
            markGenerationFinished(generationKey);
        }
    }

    private void reportDownloadProgress(int pageIndex, long received, long expected, long token, String requestedDocument) {
        mainHandler.post(() -> {
            if (!generationActive || token != generationToken || !requestedDocument.equals(documentId) || pageIndex != currentPage) {
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

    private void prefetchAhead(int fromPage) {
        int count = getPrefetchPages();
        if (count <= 0 || fromPage + 1 >= pages.size()) {
            return;
        }
        final int start = fromPage + 1;
        final ArrayList<String> requestedPages = new ArrayList<>(pages);
        final int end = Math.min(requestedPages.size(), start + count);
        final String requestedDocument = documentId;
        final String requestedSession = sessionId;
        executor.submit(() -> {
            for (int index = start; index < end; index++) {
                if (!requestedDocument.equals(documentId)) {
                    return;
                }
                String pageText = requestedPages.get(index);
                try {
                    if (findPlayableAudioFile(requestedSession, index, pageText) == null) {
                        requestSpeech(pageText, index, requestedSession, Long.MIN_VALUE, requestedDocument);
                    }
                } catch (Exception ignored) {
                    return;
                }
            }
            mainHandler.post(() -> {
                if (requestedDocument.equals(documentId)) {
                    publishSnapshot(true);
                }
            });
        });
    }

    private void ensureForeground(String text) {
        Notification notification = buildNotification(text);
        if (!foreground) {
            startForeground(NOTIFICATION_ID, notification);
            foreground = true;
        } else {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, notification);
            }
        }
    }

    private void updateNotification() {
        if (foreground) {
            ensureForeground(status);
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 10, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent toggleIntent = PendingIntent.getService(
                this, 11, serviceIntent(this, ACTION_TOGGLE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopIntent = PendingIntent.getService(
                this, 12, serviceIntent(this, ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean playing = isPlayerPlaying();
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(pages.isEmpty() || currentPage < 0 ? "Kokoro Reader" : "Kokoro Reader • page " + (currentPage + 1) + "/" + pages.size())
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(generationActive || player != null || pendingAutoAdvance != null)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(0, playing ? "Pause" : pendingAutoAdvance != null ? "Next" : "Play", toggleIntent).build())
                .addAction(new Notification.Action.Builder(0, "Stop", stopIntent).build());
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
                generationActive || player != null || pendingAutoAdvance != null,
                generationActive,
                prepared,
                playing,
                position,
                duration,
                bytesRead,
                bytesExpected,
                generationStartedAt,
                status);
        if (broadcast) {
            Intent changed = new Intent(ACTION_STATE_CHANGED).setPackage(getPackageName());
            sendBroadcast(changed);
            updateNotification();
        }
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

    private void requestAudioFocus() {
        if (audioManager == null || hasAudioFocus) {
            return;
        }
        AudioManager.OnAudioFocusChangeListener listener = focusChange -> mainHandler.post(() -> {
            if ((focusChange == AudioManager.AUDIOFOCUS_LOSS
                    || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                    && player != null && prepared && isPlayerPlaying()) {
                try {
                    player.pause();
                    releaseWakeLock();
                    status = "Paused because another app requested audio.";
                    publishSnapshot(true);
                } catch (Exception ignored) {
                }
            }
        });
        if (Build.VERSION.SDK_INT >= 26) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setOnAudioFocusChangeListener(listener)
                    .build();
            hasAudioFocus = audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            hasAudioFocus = audioManager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    private void abandonAudioFocus() {
        if (!hasAudioFocus || audioManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        }
        hasAudioFocus = false;
        audioFocusRequest = null;
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

    private void applyPlaybackRate(MediaPlayer mediaPlayer) {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                mediaPlayer.setPlaybackParams(mediaPlayer.getPlaybackParams().setSpeed(getPlaybackRate()));
            } catch (Exception ignored) {
            }
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
        if (durationMs <= 0 || pageIndex < 0 || pageIndex >= pages.size()) {
            return 0;
        }
        int units = Math.max(1, pages.get(pageIndex).length());
        int clamped = Math.max(0, Math.min(offsetUnits, units));
        return (int) Math.max(0L, Math.min(Math.max(0, durationMs - 250), clamped * (long) durationMs / units));
    }

    private File audioFileFor(int pageIndex, String text) {
        return new File(new File(getCacheDir(), "kokoro_audio"), audioFileNameFor(pageIndex, text));
    }

    private String audioFileNameFor(int pageIndex, String text) {
        String key = getTtsSettingsKey() + "\n" + pageIndex + "\n" + text;
        return String.format(Locale.US, "page_%04d_%s.%s", pageIndex + 1, sha256(key).substring(0, 24), safeExtension(getResponseFormat()));
    }

    private File findPlayableAudioFile(int pageIndex, String text) {
        return findPlayableAudioFile(sessionId, pageIndex, text);
    }

    private File findPlayableAudioFile(String requestedSessionId, int pageIndex, String text) {
        File cached = audioFileFor(pageIndex, text);
        if (cached.isFile() && cached.length() > 0L) {
            return cached;
        }
        if (requestedSessionId == null || requestedSessionId.isEmpty() || getHistoryLimit() <= 0) {
            return null;
        }
        File sessionDir = new File(new File(getFilesDir(), "kokoro_history"), safeFileName(requestedSessionId));
        File historical = new File(new File(sessionDir, "audio"), audioFileNameFor(pageIndex, text));
        if (historical.isFile() && historical.length() > 0L) {
            return historical;
        }
        File legacy = new File(sessionDir, audioFileNameFor(pageIndex, text));
        return legacy.isFile() && legacy.length() > 0L ? legacy : null;
    }

    private void persistGeneratedAudio(String requestedSessionId, int pageIndex, String text, File source) {
        if (getHistoryLimit() <= 0 || requestedSessionId == null || requestedSessionId.isEmpty()) {
            return;
        }
        try {
            File destination = new File(
                    new File(new File(new File(getFilesDir(), "kokoro_history"), safeFileName(requestedSessionId)), "audio"),
                    audioFileNameFor(pageIndex, text));
            File parent = destination.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (!destination.equals(source)) {
                copyFile(source, destination);
            }
            if (requestedSessionId.equals(sessionId)) {
                mainHandler.post(this::updateHistoryProgress);
            }
        } catch (Exception ignored) {
        }
    }

    private void updateHistoryProgress() {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        try {
            File jsonFile = new File(new File(new File(getFilesDir(), "kokoro_history"), safeFileName(sessionId)), "session.json");
            if (!jsonFile.isFile()) {
                return;
            }
            byte[] bytes;
            try (FileInputStream input = new FileInputStream(jsonFile)) {
                bytes = readAll(input);
            }
            JSONObject metadata = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            metadata.put("currentPage", currentPage);
            metadata.put("updatedAt", System.currentTimeMillis());
            metadata.put("audioCount", countAvailableAudio());
            File tmp = new File(jsonFile.getAbsolutePath() + ".service.tmp");
            try (FileOutputStream output = new FileOutputStream(tmp)) {
                output.write(metadata.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            if (jsonFile.exists()) {
                jsonFile.delete();
            }
            tmp.renameTo(jsonFile);
        } catch (Exception ignored) {
        }
    }

    private int countAvailableAudio() {
        int count = 0;
        for (int index = 0; index < pages.size(); index++) {
            if (findPlayableAudioFile(sessionId, index, pages.get(index)) != null) {
                count++;
            }
        }
        return count;
    }

    private boolean markGenerationStarted(String key) {
        synchronized (activeGenerations) {
            if (activeGenerations.contains(key)) {
                return false;
            }
            activeGenerations.add(key);
            return true;
        }
    }

    private void markGenerationFinished(String key) {
        synchronized (activeGenerations) {
            activeGenerations.remove(key);
        }
    }

    private File waitForGeneratedAudio(String requestedSessionId, int pageIndex, String text, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            File existing = findPlayableAudioFile(requestedSessionId, pageIndex, text);
            if (existing != null) {
                return existing;
            }
            Thread.sleep(350L);
        }
        return null;
    }

    private String getServerBase() {
        String value = prefString("server", BuildConfig.DEFAULT_SERVER_BASE).trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? BuildConfig.DEFAULT_SERVER_BASE : value;
    }

    private String getModel() {
        String value = prefString("model", "kokoro").trim();
        return value.isEmpty() ? "kokoro" : value;
    }

    private String getVoice() {
        String value = prefString("voice", "af_bella").trim();
        return value.isEmpty() ? "af_bella" : value;
    }

    private double getTtsSpeed() {
        try {
            return Math.max(0.25, Math.min(4.0, Double.parseDouble(prefString("speed", "1.0").trim())));
        } catch (Exception ignored) {
            return 1.0;
        }
    }

    private float getPlaybackRate() {
        try {
            return Math.max(0.25f, Math.min(4.0f, Float.parseFloat(prefString("playbackRate", "1.0").trim())));
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

    private String getResponseFormat() {
        String value = prefString("responseFormat", "mp3").trim().toLowerCase(Locale.US);
        switch (value) {
            case "opus":
            case "aac":
            case "flac":
            case "wav":
            case "pcm":
                return value;
            default:
                return "mp3";
        }
    }

    private String getLangCode() {
        return prefString("langCode", "").trim();
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
            return Math.max(0, Math.min(MAX_AUTO_NEXT_DELAY_MS, Integer.parseInt(prefString("autoNextDelayMs", String.valueOf(DEFAULT_AUTO_NEXT_DELAY_MS)).trim())));
        } catch (Exception ignored) {
            return DEFAULT_AUTO_NEXT_DELAY_MS;
        }
    }

    private String getTtsSettingsKey() {
        return getServerBase()
                + "\nmodel=" + getModel()
                + "\nvoice=" + getVoice()
                + "\ntts_speed=" + getTtsSpeed()
                + "\nformat=" + getResponseFormat()
                + "\nstream=" + prefBool("stream", true)
                + "\nlang=" + getLangCode()
                + "\nnormalize=" + prefBool("normalize", true)
                + "\nunit=" + prefBool("unitNorm", false)
                + "\nurl=" + prefBool("urlNorm", true)
                + "\nemail=" + prefBool("emailNorm", true)
                + "\nplural=" + prefBool("pluralNorm", true)
                + "\nphone=" + prefBool("phoneNorm", true);
    }

    private String prefString(String key, String fallback) {
        return prefs.getString(key, fallback);
    }

    private boolean prefBool(String key, boolean fallback) {
        return prefs.getBoolean(key, fallback);
    }

    private String acceptHeaderForFormat(String format) {
        switch (format) {
            case "wav":
                return "audio/wav, audio/*, application/octet-stream";
            case "aac":
                return "audio/aac, audio/*, application/octet-stream";
            case "flac":
                return "audio/flac, audio/*, application/octet-stream";
            case "opus":
                return "audio/opus, audio/*, application/octet-stream";
            case "pcm":
                return "audio/pcm, audio/*, application/octet-stream";
            default:
                return "audio/mpeg, audio/*, application/octet-stream";
        }
    }

    private String readError(HttpURLConnection connection) {
        try {
            InputStream stream = connection.getErrorStream();
            if (stream == null) {
                stream = connection.getInputStream();
            }
            if (stream == null) {
                return "no response body";
            }
            try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1 && output.size() < 32768) {
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } catch (Exception ex) {
            return ex.getMessage();
        }
    }

    private String safeExtension(String format) {
        return format.matches("[a-z0-9]{2,5}") ? format : "mp3";
    }

    private String safeFileName(String value) {
        String safe = value == null ? "" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return safe.isEmpty() ? "session" : safe;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) {
                result.append(String.format(Locale.US, "%02x", item));
            }
            return result.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode()) + "000000000000000000000000";
        }
    }

    private void copyFile(File source, File destination) throws Exception {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
    }

    private byte[] readAll(InputStream input) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
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
