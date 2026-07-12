package com.crall.kokororeader;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "kokoro_reader_prefs";
    private static final String DEFAULT_SERVER_BASE = BuildConfig.DEFAULT_SERVER_BASE;
    private static final int PAGE_PROGRESS_MAX = 1000;
    private static final int DEFAULT_MAX_CHARS = 900;
    private static final int MIN_MAX_CHARS = 300;
    private static final int SAFE_MAX_CHARS = 1200;
    private static final int DEFAULT_AUTO_NEXT_DELAY_MS = 650;
    private static final int MAX_AUTO_NEXT_DELAY_MS = 5000;

    private TextView statusView;
    private TextView pageLabel;
    private TextView cacheLabel;
    private PasteAwareEditText textEdit;
    private LinearLayout rootLayout;
    private SeekBar pageSeek;
    private Button prevButton;
    private Button nextButton;
    private Button playClipboardButton;
    private Button playTextButton;
    private Button clearTextButton;
    private Button settingsButton;
    private Button historyButton;
    private Button playPauseButton;
    private TextView playbackLabel;
    private TextView volumeLabel;
    private SeekBar playbackSeek;
    private SeekBar volumeSeek;

    private final ArrayList<String> pages = new ArrayList<>();
    private final HashSet<String> activeAudioGenerations = new HashSet<>();
    private final ArrayList<Integer> pageStartUnits = new ArrayList<>();
    private String fullText = "";
    private String lastStoredText;
    private int currentPage = 0;
    private int totalPlaybackUnits = 1;
    private boolean programmaticTextUpdate = false;
    private boolean repaginateBeforeNextPlayback = false;

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable historyPersistRunnable = this::persistCurrentHistoryMetadata;
    private SharedPreferences prefs;

    private boolean playbackSeekUserTouch = false;
    private int pendingSeekPageIndex = -1;
    private int pendingSeekPageOffsetUnits = -1;
    private String currentSessionId = "";
    private long currentSessionCreatedAt = 0L;
    private String activePlaybackDocumentId = "";
    private TtsConfig activePlaybackTtsConfig;
    private KokoroAudioRepository.Cancellation activityGenerationScope =
            new KokoroAudioRepository.Cancellation();
    private final KokoroAudioRepository.Cancellation activityUtilityNetworkScope =
            new KokoroAudioRepository.Cancellation();
    private volatile boolean destroyed;
    private boolean activityStarted;
    private boolean playbackReceiverRegistered = false;
    private final BroadcastReceiver playbackStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            PlaybackService.Snapshot snapshot = PlaybackService.getSnapshot();
            applyPlaybackSnapshot(snapshot, true);
            if (snapshot.queueActive) {
                startProgressTicker();
            } else {
                stopProgressTicker();
            }
        }
    };

    private final Runnable playbackProgressTicker = new Runnable() {
        @Override
        public void run() {
            updatePlaybackProgress();
        }
    };

    public static class PasteAwareEditText extends EditText {
        private Runnable pasteListener;

        public PasteAwareEditText(Context context) {
            super(context);
        }

        public void setPasteListener(Runnable pasteListener) {
            this.pasteListener = pasteListener;
        }

        @Override
        public boolean onTextContextMenuItem(int id) {
            boolean result = super.onTextContextMenuItem(id);
            if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
                if (pasteListener != null) {
                    postDelayed(pasteListener, 120);
                }
            }
            return result;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();
        boolean handledIncoming = handleIncomingIntent(getIntent(), true);
        if (!handledIncoming && prefBool("autoRestore", true)) {
            restoreLastSession(false);
        }
        if (pages.isEmpty()) {
            setStatus("Ready. Paste text here, use Share to Kokoro Reader, or tap Play Clipboard.");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent, true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        IntentFilter filter = new IntentFilter(PlaybackService.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(playbackStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(playbackStateReceiver, filter);
        }
        playbackReceiverRegistered = true;
        PlaybackService.Snapshot snapshot = PlaybackService.getSnapshot();
        updatePageViews();
        applyPlaybackSnapshot(snapshot, true);
        if (snapshot.queueActive) {
            startProgressTicker();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSession();
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        if (playbackReceiverRegistered) {
            unregisterReceiver(playbackStateReceiver);
            playbackReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        stopProgressTicker();
        cancelScheduledHistoryPersist();
        cancelActivityGenerationWork(false);
        activityUtilityNetworkScope.cancel();
        executor.shutdownNow();
        super.onDestroy();
    }

    private void ensurePlaybackNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1401);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        rootLayout = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(10));

        LinearLayout hudRow = new LinearLayout(this);
        hudRow.setOrientation(LinearLayout.HORIZONTAL);
        hudRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(hudRow, matchWrap());

        LinearLayout hudTextCol = new LinearLayout(this);
        hudTextCol.setOrientation(LinearLayout.VERTICAL);
        hudRow.addView(hudTextCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        statusView = new TextView(this);
        statusView.setTextSize(15);
        statusView.setSingleLine(false);
        statusView.setMaxLines(2);
        hudTextCol.addView(statusView, matchWrap());

        cacheLabel = new TextView(this);
        cacheLabel.setTextSize(12);
        cacheLabel.setSingleLine(true);
        hudTextCol.addView(cacheLabel, matchWrap());

        historyButton = new Button(this);
        historyButton.setText("History");
        historyButton.setAllCaps(false);
        historyButton.setTextSize(16);
        historyButton.setMinHeight(dp(52));
        hudRow.addView(historyButton, new LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT));

        settingsButton = new Button(this);
        settingsButton.setText("Settings");
        settingsButton.setAllCaps(false);
        settingsButton.setTextSize(16);
        settingsButton.setMinHeight(dp(52));
        hudRow.addView(settingsButton, new LinearLayout.LayoutParams(dp(108), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setGravity(Gravity.CENTER_VERTICAL);
        navRow.setPadding(0, dp(8), 0, dp(2));
        root.addView(navRow, matchWrap());

        prevButton = new Button(this);
        prevButton.setText("◀");
        prevButton.setTextSize(34);
        prevButton.setAllCaps(false);
        prevButton.setMinHeight(dp(78));
        LinearLayout.LayoutParams prevParams = new LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT);
        prevParams.setMargins(0, 0, dp(8), 0);
        navRow.addView(prevButton, prevParams);

        pageLabel = new TextView(this);
        pageLabel.setTextSize(22);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setSingleLine(false);
        pageLabel.setMaxLines(2);
        navRow.addView(pageLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        nextButton = new Button(this);
        nextButton.setText("▶");
        nextButton.setTextSize(34);
        nextButton.setAllCaps(false);
        nextButton.setMinHeight(dp(78));
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT);
        nextParams.setMargins(dp(8), 0, 0, 0);
        navRow.addView(nextButton, nextParams);

        pageSeek = new SeekBar(this);
        pageSeek.setMax(0);
        root.addView(pageSeek, matchWrap());

        playbackLabel = new TextView(this);
        playbackLabel.setTextSize(13);
        playbackLabel.setSingleLine(false);
        playbackLabel.setMaxLines(2);
        playbackLabel.setText("Playback: idle");
        playbackLabel.setPadding(0, dp(6), 0, 0);
        root.addView(playbackLabel, matchWrap());

        playbackSeek = new SeekBar(this);
        playbackSeek.setMax(PAGE_PROGRESS_MAX);
        playbackSeek.setProgress(0);
        root.addView(playbackSeek, matchWrap());

        LinearLayout mediaRow = new LinearLayout(this);
        mediaRow.setOrientation(LinearLayout.HORIZONTAL);
        mediaRow.setGravity(Gravity.CENTER_VERTICAL);
        mediaRow.setPadding(0, dp(2), 0, dp(6));
        root.addView(mediaRow, matchWrap());

        playPauseButton = new Button(this);
        playPauseButton.setText("Play");
        playPauseButton.setAllCaps(false);
        playPauseButton.setTextSize(18);
        playPauseButton.setMinHeight(dp(56));
        mediaRow.addView(playPauseButton, new LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));

        volumeLabel = new TextView(this);
        volumeLabel.setTextSize(13);
        volumeLabel.setGravity(Gravity.CENTER);
        volumeLabel.setText("Vol " + getVolumePercent() + "%");
        mediaRow.addView(volumeLabel, new LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT));

        volumeSeek = new SeekBar(this);
        volumeSeek.setMax(100);
        volumeSeek.setProgress(getVolumePercent());
        mediaRow.addView(volumeSeek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        textEdit = new PasteAwareEditText(this);
        textEdit.setTextSize(22);
        textEdit.setGravity(Gravity.TOP | Gravity.START);
        textEdit.setSingleLine(false);
        textEdit.setMinLines(7);
        textEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        textEdit.setHint("Paste long text here. The app will paginate pasted text so you can flip pages with the arrows, then tap Play all.");
        textEdit.setPadding(dp(12), dp(12), dp(12), dp(12));
        textEdit.setPasteListener(() -> {
            if (!programmaticTextUpdate) {
                paginateTextBox(false);
            }
        });
        root.addView(textEdit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER_VERTICAL);
        buttonRow.setPadding(0, dp(10), 0, 0);
        root.addView(buttonRow, matchWrap());

        playClipboardButton = new Button(this);
        playClipboardButton.setText("Play Clipboard");
        playClipboardButton.setAllCaps(false);
        playClipboardButton.setTextSize(28);
        playClipboardButton.setMinHeight(dp(96));
        LinearLayout.LayoutParams clipParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2);
        clipParams.setMargins(0, 0, dp(6), 0);
        buttonRow.addView(playClipboardButton, clipParams);

        playTextButton = new Button(this);
        playTextButton.setText("Play Text");
        playTextButton.setAllCaps(false);
        playTextButton.setTextSize(22);
        playTextButton.setMinHeight(dp(96));
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.setMargins(dp(6), 0, dp(6), 0);
        buttonRow.addView(playTextButton, textParams);

        clearTextButton = new Button(this);
        clearTextButton.setText("Clear Text");
        clearTextButton.setAllCaps(false);
        clearTextButton.setTextSize(18);
        clearTextButton.setMinHeight(dp(96));
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        clearParams.setMargins(dp(6), 0, 0, 0);
        buttonRow.addView(clearTextButton, clearParams);

        historyButton.setOnClickListener(v -> showHistoryDialog());
        settingsButton.setOnClickListener(v -> showSettingsDialog());
        prevButton.setOnClickListener(v -> movePage(-1));
        nextButton.setOnClickListener(v -> movePage(1));
        playClipboardButton.setOnClickListener(v -> readClipboardSplitGeneratePlay());
        playTextButton.setOnClickListener(v -> playAllFromCurrentPage());
        clearTextButton.setOnClickListener(v -> showClearTextDialog());
        playPauseButton.setOnClickListener(v -> togglePlayPause());

        playbackSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    updatePlaybackLabelForScrub(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                playbackSeekUserTouch = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                playbackSeekUserTouch = false;
                seekPlaybackTo(seekBar.getProgress());
            }
        });

        volumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    setPlaybackVolumePercent(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        pageSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && !pages.isEmpty()) {
                    currentPage = Math.max(0, Math.min(progress, pages.size() - 1));
                    saveSession();
                    updatePageViews();
                    setStatus("Selected page " + (currentPage + 1) + ". Tap Play all to start here.");
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        updatePageViews();
        applyAppTheme();
        setContentView(root);
    }

    private boolean handleIncomingIntent(Intent intent, boolean autoStart) {
        if (intent == null) {
            return false;
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (shared == null) {
                shared = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            }
            if (shared != null && shared.length() > 0) {
                setFullTextAndPaginate(shared.toString(), 0, autoStart, "Received shared text.");
                return true;
            }
        }
        return false;
    }

    private void readClipboardSplitGeneratePlay() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            toast("Clipboard is empty.");
            return;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            toast("Clipboard is empty.");
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text == null || text.length() == 0) {
            toast("Clipboard does not contain text.");
            return;
        }
        setFullTextAndPaginate(text.toString(), 0, true, "Clipboard paginated.");
    }

    private void playAllFromCurrentPage() {
        String visible = textEdit == null ? "" : textEdit.getText().toString();
        boolean needsPagination = pages.isEmpty();
        if (!needsPagination && currentPage >= 0 && currentPage < pages.size()) {
            needsPagination = !visible.trim().equals(pages.get(currentPage).trim());
        }
        if (needsPagination) {
            repaginateBeforeNextPlayback = false;
            paginateTextBox(true);
            return;
        }
        if (repaginateBeforeNextPlayback && fullText != null && !fullText.trim().isEmpty()) {
            repaginateForCurrentSettings();
        }
        clearPendingDocumentSeek();
        generateAndPlayCurrentPage();
    }

    private void repaginateForCurrentSettings() {
        int oldStartUnits = getPageStartUnit(Math.max(0, Math.min(currentPage, Math.max(0, pages.size() - 1))));
        int oldTotalUnits = Math.max(1, totalPlaybackUnits);
        double progressFraction = oldStartUnits / (double) oldTotalUnits;
        pages.clear();
        pages.addAll(splitIntoPages(fullText, getMaxChars()));
        rebuildPlaybackProgressIndex();
        int targetUnits = (int) Math.round(progressFraction * totalPlaybackUnits);
        currentPage = 0;
        for (int index = 0; index < pages.size(); index++) {
            if (getPageStartUnit(index) <= targetUnits) {
                currentPage = index;
            } else {
                break;
            }
        }
        repaginateBeforeNextPlayback = false;
        updatePageViews();
    }

    private void showClearTextDialog() {
        String editorText = textEdit == null ? "" : textEdit.getText().toString();
        boolean hasText = (fullText != null && !fullText.trim().isEmpty()) || !editorText.trim().isEmpty() || !pages.isEmpty();
        if (!hasText) {
            clearCurrentTextSession("Text is already clear.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Clear current text? History/audio are kept.")
                .setPositiveButton("Clear", (dialog, which) -> clearCurrentTextSession("Cleared current text. Speech history is kept."))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showClearHistoryDialog() {
        ArrayList<JSONObject> sessions = readHistorySessions();
        if (sessions.isEmpty()) {
            setStatus("Speech history is already empty.");
            toast("History empty");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Clear saved speech history?")
                .setMessage("This deletes saved session metadata and offline audio. The current text editor and transient cache are kept.")
                .setPositiveButton("Clear History", (dialog, which) -> clearHistoryStorage())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearHistoryStorage() {
        cancelScheduledHistoryPersist();
        cancelActivityGenerationWork(true);
        PlaybackService.clearHistory(this);
        stopProgressTicker();
        activePlaybackDocumentId = "";
        activePlaybackTtsConfig = null;
        currentSessionId = "";
        currentSessionCreatedAt = 0L;
        updatePageViews();
        setStatus("Clearing speech history storage. Current text is kept.");
    }

    private void clearCurrentTextSession(String status) {
        cancelScheduledHistoryPersist();
        cancelActivityGenerationWork(true);
        releasePlayer();
        fullText = "";
        repaginateBeforeNextPlayback = false;
        pages.clear();
        pageStartUnits.clear();
        totalPlaybackUnits = 1;
        currentPage = 0;
        currentSessionId = "";
        currentSessionCreatedAt = 0L;
        clearPendingDocumentSeek();
        if (textEdit != null) {
            programmaticTextUpdate = true;
            textEdit.setText("");
            programmaticTextUpdate = false;
        }
        CurrentTextStore.clear(this);
        lastStoredText = "";
        prefs.edit()
                .remove("lastText")
                .putInt("lastPage", 0)
                .apply();
        updatePageViews();
        setStatus(status);
    }

    private boolean ensurePagesFromTextBox() {
        String visible = textEdit.getText().toString();
        if (pages.isEmpty()) {
            return paginateTextBox(false);
        }
        String currentVisiblePage = pages.get(currentPage);
        if (!visible.trim().equals(currentVisiblePage.trim())) {
            return paginateTextBox(false);
        }
        return true;
    }

    private boolean paginateTextBox(boolean autoPlay) {
        String raw = textEdit.getText().toString();
        if (raw.trim().isEmpty()) {
            toast("Paste or share some text first.");
            return false;
        }
        setFullTextAndPaginate(raw, 0, autoPlay, "Text paginated.");
        return !pages.isEmpty();
    }

    private void setFullTextAndPaginate(String raw, int desiredPage, boolean autoPlay, String prefix) {
        cancelActivityGenerationWork(true);
        if (!autoPlay) {
            releasePlayer();
        }
        String normalized = normalizeCopiedText(raw);
        fullText = normalized;
        repaginateBeforeNextPlayback = false;
        pages.clear();
        pages.addAll(splitIntoPages(normalized, getMaxChars()));
        rebuildPlaybackProgressIndex();
        ensureCurrentHistorySession();
        if (pages.isEmpty()) {
            currentPage = 0;
            updatePageViews();
            setStatus("No text to paginate.");
            saveSession();
            return;
        }
        currentPage = Math.max(0, Math.min(desiredPage, pages.size() - 1));
        updatePageViews();
        saveSession();
        setStatus(prefix + " " + pages.size() + " page(s)." + (autoPlay ? " Generating page " + (currentPage + 1) + "." : " Use arrows to choose a page, then Play all."));
        if (autoPlay) {
            generateAndPlayCurrentPage();
        }
    }

    private String normalizeCopiedText(String raw) {
        return TextPaginator.normalizeCopiedText(raw);
    }

    private ArrayList<String> splitIntoPages(String text, int maxChars) {
        return TextPaginator.splitIntoPages(text, maxChars);
    }

    private void movePage(int delta) {
        if (!ensurePagesFromTextBox()) {
            return;
        }
        int next = Math.max(0, Math.min(currentPage + delta, pages.size() - 1));
        if (next == currentPage) {
            if (pages.size() <= 1) {
                setStatus("Only one page.");
            }
            return;
        }
        currentPage = next;
        releasePlayer();
        saveSession();
        updatePageViews();
        setStatus("Selected page " + (currentPage + 1) + ". Tap Play all to start here.");
    }

    private void generateAndPlayCurrentPage() {
        if (pages.isEmpty()) {
            if (!paginateTextBox(false)) {
                return;
            }
        }
        ensureCurrentHistorySession();
        saveSession();
        try {
            PlaybackDocumentStore.Document document = PlaybackDocumentStore.write(
                    this, pages, currentSessionId, currentPage, getTtsConfig());
            activePlaybackDocumentId = document.id;
            activePlaybackTtsConfig = document.ttsConfig;
            setEstimatedPlaybackProgress(currentPage, "Preparing page " + (currentPage + 1) + " in background");
            setStatus("Starting background playback for page " + (currentPage + 1) + " of " + pages.size() + "...");
            ensurePlaybackNotificationPermission();
            PlaybackService.playDocument(this, currentPage, pendingSeekPageOffsetUnits);
            clearPendingDocumentSeek();
            startProgressTicker();
        } catch (Exception ex) {
            setStatus("Could not start background playback: " + ex.getMessage());
        }
    }

    private void pregenerateNextPages(int count, boolean announce) {
        if (hasActiveOrPendingPlayback()) {
            setStatus("Background playback already owns prefetching for the active document.");
            return;
        }
        pregeneratePagesFrom(currentPage + 1, count, announce);
    }

    private void pregeneratePagesFrom(int startPageIndex, int count, boolean announce) {
        if (pages.isEmpty() || count <= 0) {
            return;
        }
        final ArrayList<String> requestedPages = new ArrayList<>(pages);
        final int start = Math.max(0, Math.min(startPageIndex, requestedPages.size()));
        final int end = Math.min(requestedPages.size(), start + count);
        final String sessionId = currentSessionId;
        final TtsConfig config = getTtsConfig();
        final int historyLimit = getHistoryLimit();
        final KokoroAudioRepository.Cancellation generationScope = activityGenerationScope;
        if (start >= end) {
            return;
        }
        if (announce) {
            setStatus("Pre-generating pages " + (start + 1) + " through " + end + "...");
        }
        executor.submit(() -> {
            int generated = 0;
            for (int i = start; i < end; i++) {
                String pageText = requestedPages.get(i);
                try {
                    if (KokoroAudioRepository.findPlayable(
                            this, config, historyLimit, sessionId, i, pageText) == null) {
                        requestSpeech(pageText, i, sessionId, config, historyLimit, generationScope);
                        generated++;
                    }
                } catch (InterruptedException ignored) {
                    return;
                } catch (Exception ex) {
                    if (announce && !generationScope.isCancelled()) {
                        final int failedPage = i + 1;
                        postIfActivityAlive(() -> setStatus(
                                "Pre-generation failed on page " + failedPage + ": " + ex.getMessage()));
                    }
                    return;
                }
            }
            if (generationScope.isCancelled()) {
                return;
            }
            final int finalGenerated = generated;
            postIfActivityAlive(() -> {
                updatePageViews();
                if (announce) {
                    setStatus("Pre-generation done. New pages generated: " + finalGenerated + ".");
                }
            });
        });
    }

    private File requestSpeech(
            String text,
            int pageIndex,
            String sessionId,
            TtsConfig config,
            int historyLimit,
            KokoroAudioRepository.Cancellation generationScope) throws Exception {
        File cached = KokoroAudioRepository.cacheFile(this, config, pageIndex, text);
        String generationKey = cached.getAbsolutePath();
        boolean generationMarkerOwner = markAudioGenerationStarted(generationKey);
        try {
            return KokoroAudioRepository.requestSpeech(
                    this,
                    config,
                    historyLimit,
                    sessionId,
                    pageIndex,
                    text,
                    generationScope,
                    null);
        } finally {
            if (generationMarkerOwner) {
                markAudioGenerationFinished(generationKey);
            }
        }
    }

    private String readError(HttpURLConnection conn) {
        try {
            InputStream es = conn.getErrorStream();
            if (es == null) {
                es = conn.getInputStream();
            }
            if (es == null) {
                return "no response body";
            }
            try (InputStream is = es; ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = is.read(buffer)) != -1 && baos.size() < 32768) {
                    baos.write(buffer, 0, n);
                }
                return baos.toString("UTF-8");
            }
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private void setEstimatedPlaybackProgress(int pageIndex, String message) {
        if (playPauseButton != null) {
            playPauseButton.setText("Play");
            playPauseButton.setEnabled(false);
        }
        if (playbackSeek != null) {
            playbackSeek.setMax(getPlaybackBarMax());
            playbackSeek.setProgress(getPlaybackBarStartForPage(pageIndex));
        }
        setPlaybackLabel(message + " (" + getPlaybackScopeName() + " estimate)");
    }

    private void togglePlayPause() {
        PlaybackService.Snapshot snapshot = PlaybackService.getSnapshot();
        if (snapshot.prepared || snapshot.generationActive) {
            PlaybackService.toggle(this);
            applyPlaybackSnapshot(PlaybackService.getSnapshot(), false);
            startProgressTicker();
            return;
        }
        playAllFromCurrentPage();
    }

    private void seekPlaybackTo(int progress) {
        if (pages.isEmpty()) {
            setStatus("No text loaded to seek.");
            return;
        }
        DocumentSeekTarget target = playbackSeekTargetForProgress(progress);
        PlaybackService.Snapshot activeSnapshot = PlaybackService.getSnapshot();
        if (!hasActiveOrPendingPlayback()) {
            ensureCurrentHistorySession();
        }
        TtsConfig seekConfig = getEffectiveTtsConfig();
        currentPage = target.pageIndex;
        pendingSeekPageIndex = target.pageIndex;
        pendingSeekPageOffsetUnits = target.pageOffsetUnits;
        saveSession();
        updatePageViews();
        try {
            PlaybackDocumentStore.Document document = PlaybackDocumentStore.write(
                    this, pages, currentSessionId, currentPage, seekConfig);
            activePlaybackDocumentId = document.id;
            activePlaybackTtsConfig = document.ttsConfig;
            ensurePlaybackNotificationPermission();
            PlaybackService.seek(this, target.pageIndex, target.pageOffsetUnits);
            setStatus((useWholeTextProgress() ? "Seeking whole text to page " : "Seeking current page ")
                    + (target.pageIndex + 1) + ".");
            clearPendingDocumentSeek();
            startProgressTicker();
        } catch (Exception ex) {
            setStatus("Seek failed: " + ex.getMessage());
        }
    }

    private void updatePlaybackLabelForScrub(int progress) {
        if (playbackLabel == null || playbackSeek == null) {
            return;
        }
        if (pages.isEmpty()) {
            setPlaybackLabel("Playback: idle");
            return;
        }
        DocumentSeekTarget target = playbackSeekTargetForProgress(progress);
        int percent = Math.round((clampPlaybackBarProgress(progress) * 100f) / Math.max(1, getPlaybackBarMax()));
        PlaybackService.Snapshot snapshot = PlaybackService.getSnapshot();
        if (snapshot.prepared && snapshot.pageIndex == target.pageIndex && snapshot.durationMs > 0) {
            int seekMs = msForPageOffsetUnits(target.pageIndex, target.pageOffsetUnits, snapshot.durationMs);
            String noun = useWholeTextProgress() ? "document" : "page";
            setPlaybackLabel("Seek " + noun + " to " + percent + "%: page " + (target.pageIndex + 1)
                    + ", " + formatDuration(seekMs) + " / " + formatDuration(snapshot.durationMs));
            return;
        }
        if (useWholeTextProgress()) {
            setPlaybackLabel("Seek document to " + percent + "%: page " + (target.pageIndex + 1)
                    + " (estimate until that page audio is ready)");
        } else {
            setPlaybackLabel("Seek current page to " + percent + "% (estimate until audio is ready)");
        }
    }

    private void startProgressTicker() {
        mainHandler.removeCallbacks(playbackProgressTicker);
        mainHandler.post(playbackProgressTicker);
    }

    private void stopProgressTicker() {
        mainHandler.removeCallbacks(playbackProgressTicker);
    }

    private void updatePlaybackProgress() {
        PlaybackService.Snapshot snapshot = PlaybackService.getSnapshot();
        applyPlaybackSnapshot(snapshot, false);
        mainHandler.removeCallbacks(playbackProgressTicker);
        if (snapshot.queueActive) {
            mainHandler.postDelayed(playbackProgressTicker, snapshot.playing ? 500L : 1000L);
        }
    }

    private void applyPlaybackSnapshot(PlaybackService.Snapshot snapshot, boolean announceStatus) {
        if (snapshot == null) {
            return;
        }
        if (!snapshot.isActive() && snapshot.documentId.isEmpty()
                && !storedPlaybackDocumentMatchesActiveId()) {
            activePlaybackDocumentId = "";
            activePlaybackTtsConfig = null;
        }
        if (snapshot.isActive() && activePlaybackDocumentId.isEmpty()
                && snapshotMatchesCurrentDocument(snapshot)) {
            activePlaybackDocumentId = snapshot.documentId;
        }
        if (!snapshot.documentId.isEmpty() && !activePlaybackDocumentId.isEmpty()
                && !snapshot.documentId.equals(activePlaybackDocumentId)) {
            return;
        }

        if (snapshot.pageIndex >= 0 && snapshot.pageIndex < pages.size() && currentPage != snapshot.pageIndex) {
            currentPage = snapshot.pageIndex;
            saveSession();
            updatePageViews();
        }

        if (snapshot.generationActive && snapshot.pageIndex >= 0 && snapshot.pageIndex < pages.size()) {
            long elapsed = Math.max(0L, System.currentTimeMillis() - snapshot.generationStartedAt);
            int pageUnits = getPageProgressUnits(snapshot.pageIndex);
            int pageProgress;
            String detail;
            if (snapshot.bytesExpected > 0L) {
                pageProgress = (int) Math.max(0L, Math.min(pageUnits - 1L,
                        snapshot.bytesRead * (long) pageUnits / Math.max(1L, snapshot.bytesExpected)));
                detail = String.format(Locale.US, "Receiving Kokoro audio: %d%%",
                        Math.max(0L, Math.min(99L, snapshot.bytesRead * 100L / Math.max(1L, snapshot.bytesExpected))));
            } else if (snapshot.bytesRead > 0L) {
                pageProgress = (int) Math.max(1L, Math.min(pageUnits - 1L, elapsed / 300L));
                detail = "Receiving Kokoro audio: " + formatBytes(snapshot.bytesRead);
            } else {
                pageProgress = (int) Math.max(0L, Math.min(pageUnits - 1L, elapsed / 300L));
                detail = "Preparing audio...";
            }
            int playbackProgress = getPlaybackBarProgressForPageOffsetUnits(snapshot.pageIndex, pageProgress);
            if (playbackSeek != null && !playbackSeekUserTouch) {
                playbackSeek.setMax(getPlaybackBarMax());
                playbackSeek.setProgress(playbackProgress);
            }
            setPlaybackLabel(detail + " (background " + getPlaybackScopeName() + " estimate; "
                    + getTrackBuildStatusLabel() + ")");
        } else if (snapshot.prepared && snapshot.pageIndex >= 0 && snapshot.pageIndex < pages.size()) {
            int duration = snapshot.durationMs;
            int position = snapshot.positionMs;
            if (duration > 0) {
                int playbackProgress = getPlaybackBarProgressForPagePosition(snapshot.pageIndex, position, duration);
                if (playbackSeek != null && !playbackSeekUserTouch) {
                    playbackSeek.setMax(getPlaybackBarMax());
                    playbackSeek.setProgress(playbackProgress);
                }
                int percent = Math.round((playbackProgress * 100f) / Math.max(1, getPlaybackBarMax()));
                if (useWholeTextProgress()) {
                    String estimateSuffix = allPagesCached() ? "" : " est";
                    setPlaybackLabel(String.format(Locale.US,
                            "Document%s: %d%% • page %d/%d: %s / %s • background playback • %s",
                            estimateSuffix, percent, snapshot.pageIndex + 1, pages.size(),
                            formatDuration(position), formatDuration(duration), getTrackBuildStatusLabel()));
                } else {
                    setPlaybackLabel(String.format(Locale.US,
                            "Page: %d%% • page %d/%d: %s / %s • background playback",
                            percent, snapshot.pageIndex + 1, pages.size(),
                            formatDuration(position), formatDuration(duration)));
                }
            } else {
                setPlaybackLabel(snapshot.status);
            }
        } else if (!snapshot.status.isEmpty()) {
            setPlaybackLabel(snapshot.status);
        }

        if (announceStatus && !snapshot.status.equals("Playback: idle")) {
            setStatus(snapshot.status);
        }
        updatePlayPauseButton();
    }

    private boolean snapshotMatchesCurrentDocument(PlaybackService.Snapshot snapshot) {
        if (snapshot == null || snapshot.documentId.isEmpty()) {
            return false;
        }
        try {
            PlaybackDocumentStore.Document document = PlaybackDocumentStore.read(this);
            boolean matches = snapshot.documentId.equals(document.id) && document.pages.equals(pages);
            if (matches) {
                activePlaybackTtsConfig = document.ttsConfig;
            }
            return matches;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void resetPlaybackControls(String label) {
        stopProgressTicker();
        clearPendingDocumentSeek();
        if (playbackSeek != null && !playbackSeekUserTouch) {
            playbackSeek.setMax(getPlaybackBarMax());
            playbackSeek.setProgress(0);
        }
        setPlaybackLabel(label);
        updatePlayPauseButton();
    }

    private void setPlaybackLabel(String label) {
        if (playbackLabel != null) {
            playbackLabel.setText(label == null ? "Playback: idle" : label);
        }
    }

    private void updatePlayPauseButton() {
        if (playPauseButton == null) {
            return;
        }
        PlaybackService.Snapshot snapshot = PlaybackService.getSnapshot();
        playPauseButton.setText(snapshot.playing ? "Pause" : "Play");
        playPauseButton.setEnabled(!pages.isEmpty()
                && !snapshot.generationActive
                && (!snapshot.queueActive || snapshot.prepared));
    }

    private void setPlaybackVolumePercent(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        prefs.edit().putString("volumePercent", String.valueOf(clamped)).apply();
        if (volumeLabel != null) {
            volumeLabel.setText("Vol " + clamped + "%");
        }
        applyPlaybackVolume();
    }

    private int getVolumePercent() {
        try {
            int value = Integer.parseInt(prefString("volumePercent", "100").trim());
            return Math.max(0, Math.min(100, value));
        } catch (Exception ex) {
            return 100;
        }
    }

    private float getPlaybackVolume() {
        return Math.max(0f, Math.min(1f, getVolumePercent() / 100f));
    }

    private void applyPlaybackVolume() {
        PlaybackService.setVolume(this, getPlaybackVolume());
    }

    private boolean useWholeTextProgress() {
        return prefBool("wholeTextProgress", true);
    }

    private String getPlaybackScopeName() {
        return useWholeTextProgress() ? "whole-text" : "current-page";
    }

    private int getPlaybackBarMax() {
        return useWholeTextProgress() ? getWholePlaybackMax() : PAGE_PROGRESS_MAX;
    }

    private int clampPlaybackBarProgress(int progress) {
        return Math.max(0, Math.min(progress, getPlaybackBarMax()));
    }

    private int getPlaybackBarStartForPage(int pageIndex) {
        return useWholeTextProgress() ? getPageStartUnit(pageIndex) : 0;
    }

    private int getPlaybackBarProgressForPageOffsetUnits(int pageIndex, int pageOffsetUnits) {
        int pageUnits = getPageProgressUnits(pageIndex);
        int clampedOffset = Math.max(0, Math.min(pageOffsetUnits, pageUnits));
        if (useWholeTextProgress()) {
            return clampPlaybackBarProgress(getPageStartUnit(pageIndex) + clampedOffset);
        }
        return clampPlaybackBarProgress((int) ((clampedOffset * (long) PAGE_PROGRESS_MAX) / Math.max(1, pageUnits)));
    }

    private int getPlaybackBarProgressForPagePosition(int pageIndex, int positionMs, int durationMs) {
        int pageUnits = getPageProgressUnits(pageIndex);
        if (durationMs <= 0) {
            return getPlaybackBarStartForPage(pageIndex);
        }
        int pageOffset = (int) Math.max(0, Math.min(pageUnits, (positionMs * (long) pageUnits) / Math.max(1, durationMs)));
        return getPlaybackBarProgressForPageOffsetUnits(pageIndex, pageOffset);
    }

    private DocumentSeekTarget playbackSeekTargetForProgress(int progress) {
        if (pages.isEmpty()) {
            return new DocumentSeekTarget(0, 0);
        }
        if (!useWholeTextProgress()) {
            int pageIndex = Math.max(0, Math.min(currentPage, pages.size() - 1));
            int pageUnits = getPageProgressUnits(pageIndex);
            int offset = (int) Math.max(0, Math.min(pageUnits, (clampPlaybackBarProgress(progress) * (long) pageUnits) / Math.max(1, PAGE_PROGRESS_MAX)));
            return new DocumentSeekTarget(pageIndex, offset);
        }
        int clamped = clampWholePlaybackProgress(progress);
        int pageIndex = pages.size() - 1;
        for (int i = 0; i < pages.size(); i++) {
            if (clamped < getPageEndUnit(i)) {
                pageIndex = i;
                break;
            }
        }
        int offset = Math.max(0, Math.min(getPageProgressUnits(pageIndex), clamped - getPageStartUnit(pageIndex)));
        return new DocumentSeekTarget(pageIndex, offset);
    }

    private static class DocumentSeekTarget {
        final int pageIndex;
        final int pageOffsetUnits;

        DocumentSeekTarget(int pageIndex, int pageOffsetUnits) {
            this.pageIndex = pageIndex;
            this.pageOffsetUnits = pageOffsetUnits;
        }
    }

    private void rebuildPlaybackProgressIndex() {
        pageStartUnits.clear();
        int cursor = 0;
        for (int i = 0; i < pages.size(); i++) {
            pageStartUnits.add(cursor);
            int units = Math.max(1, pages.get(i).length());
            if (Integer.MAX_VALUE - cursor < units) {
                cursor = Integer.MAX_VALUE;
                break;
            }
            cursor += units;
        }
        totalPlaybackUnits = Math.max(1, cursor);
    }

    private int getWholePlaybackMax() {
        if (pageStartUnits.size() != pages.size()) {
            rebuildPlaybackProgressIndex();
        }
        return Math.max(1, totalPlaybackUnits);
    }

    private int clampWholePlaybackProgress(int progress) {
        return Math.max(0, Math.min(progress, getWholePlaybackMax()));
    }

    private int getPageProgressUnits(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= pages.size()) {
            return 1;
        }
        return Math.max(1, pages.get(pageIndex).length());
    }

    private int getPageStartUnit(int pageIndex) {
        if (pageStartUnits.size() != pages.size()) {
            rebuildPlaybackProgressIndex();
        }
        if (pageIndex <= 0) {
            return 0;
        }
        if (pageIndex >= pageStartUnits.size()) {
            return getWholePlaybackMax();
        }
        return pageStartUnits.get(pageIndex);
    }

    private int getPageEndUnit(int pageIndex) {
        if (pageIndex < 0) {
            return 0;
        }
        if (pageIndex >= pages.size() - 1) {
            return getWholePlaybackMax();
        }
        return getPageStartUnit(pageIndex + 1);
    }

    private int msForPageOffsetUnits(int pageIndex, int offsetUnits, int durationMs) {
        if (durationMs <= 0) {
            return 0;
        }
        int units = getPageProgressUnits(pageIndex);
        int clampedOffset = Math.max(0, Math.min(offsetUnits, units));
        return (int) Math.max(0, Math.min(Math.max(0, durationMs - 250), (clampedOffset * (long) durationMs) / Math.max(1, units)));
    }

    private boolean allPagesCached() {
        if (pages.isEmpty()) {
            return false;
        }
        for (int i = 0; i < pages.size(); i++) {
            if (!audioAvailableForPage(i, pages.get(i))) {
                return false;
            }
        }
        return true;
    }

    private void clearPendingDocumentSeek() {
        pendingSeekPageIndex = -1;
        pendingSeekPageOffsetUnits = -1;
    }

    private String formatDuration(int ms) {
        int totalSeconds = Math.max(0, ms / 1000);
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kib = bytes / 1024.0;
        if (kib < 1024) {
            return String.format(Locale.US, "%.1f KiB", kib);
        }
        return String.format(Locale.US, "%.1f MiB", kib / 1024.0);
    }

    private void cancelActivityGenerationWork(boolean resetForFutureWork) {
        activityGenerationScope.cancel();
        if (resetForFutureWork) {
            activityGenerationScope = new KokoroAudioRepository.Cancellation();
        }
    }

    private void releasePlayer() {
        PlaybackService.stop(this);
        stopProgressTicker();
        activePlaybackDocumentId = "";
        activePlaybackTtsConfig = null;
        resetPlaybackControls("Playback: idle");
    }

    private void updatePageViews() {
        if (textEdit == null || pageLabel == null || pageSeek == null) {
            return;
        }
        TtsConfig displayConfig = getEffectiveTtsConfig();
        if (pages.isEmpty()) {
            pageLabel.setText("No pages yet");
            cacheLabel.setText("Server: " + displayConfig.serverBase + "  Voice: "
                    + displayConfig.voice + " • " + getTrackBuildStatusLabel());
            pageSeek.setMax(0);
            pageSeek.setProgress(0);
            if (playbackSeek != null && !playbackSeekUserTouch) {
                playbackSeek.setMax(getPlaybackBarMax());
                playbackSeek.setProgress(0);
            }
            prevButton.setEnabled(false);
            nextButton.setEnabled(false);
            updatePlayPauseButton();
            return;
        }
        currentPage = Math.max(0, Math.min(currentPage, pages.size() - 1));
        String text = pages.get(currentPage);
        String cachedText = audioAvailableForPage(currentPage, text) ? "cached/saved" : "not cached";
        pageLabel.setText(String.format(Locale.US, "Page %d / %d", currentPage + 1, pages.size()));
        cacheLabel.setText(String.format(
                Locale.US,
                "%s • %,d chars • %s • %s @ %.2fx • %s",
                cachedText,
                text.length(),
                displayConfig.voice,
                displayConfig.responseFormat,
                displayConfig.speed,
                getTrackBuildStatusLabel()));
        pageSeek.setMax(Math.max(0, pages.size() - 1));
        pageSeek.setProgress(currentPage);
        prevButton.setEnabled(currentPage > 0);
        nextButton.setEnabled(currentPage + 1 < pages.size());
        programmaticTextUpdate = true;
        textEdit.setText(text);
        textEdit.setSelection(0);
        programmaticTextUpdate = false;
        updatePlayPauseButton();
    }

    private File getAudioCacheDir() {
        return KokoroAudioRepository.cacheDirectory(this);
    }

    private int clearAudioCache() {
        cancelActivityGenerationWork(true);
        PlaybackService.clearAudioCache(this);
        stopProgressTicker();
        activePlaybackDocumentId = "";
        activePlaybackTtsConfig = null;
        updatePageViews();
        setStatus("Clearing transient audio cache. Saved history audio is kept.");
        return 0;
    }

    private int deleteChildren(File dir) {
        if (dir == null || !dir.exists()) {
            return 0;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (File f : files) {
            if (f.isDirectory()) {
                count += deleteChildren(f);
            }
            if (f.delete()) {
                count++;
            }
        }
        return count;
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }

    private void saveSession() {
        String savedText = fullText;
        if ((savedText == null || savedText.trim().isEmpty()) && textEdit != null) {
            savedText = textEdit.getText().toString();
        }
        persistCurrentText(savedText == null ? "" : savedText);
        prefs.edit().putInt("lastPage", currentPage).apply();
        scheduleHistoryMetadataPersist();
    }

    private void scheduleHistoryMetadataPersist() {
        mainHandler.removeCallbacks(historyPersistRunnable);
        mainHandler.postDelayed(historyPersistRunnable, 350L);
    }

    private void cancelScheduledHistoryPersist() {
        mainHandler.removeCallbacks(historyPersistRunnable);
    }

    private void persistCurrentText(String text) {
        if (text.equals(lastStoredText)) {
            return;
        }
        try {
            CurrentTextStore.write(this, text);
            lastStoredText = text;
            // Remove the legacy large SharedPreferences value after successful migration/write.
            prefs.edit().remove("lastText").apply();
        } catch (Exception ignored) {
            // Keep the in-memory editor usable even if durable storage is temporarily unavailable.
        }
    }

    private void restoreLastSession(boolean autoPlay) {
        String text = "";
        try {
            text = CurrentTextStore.read(this);
        } catch (Exception ignored) {
        }
        if (text == null || text.trim().isEmpty()) {
            // One-time compatibility path for releases that stored the full document in preferences.
            text = prefs.getString("lastText", "");
            if (text != null && !text.trim().isEmpty()) {
                persistCurrentText(text);
            }
        } else {
            lastStoredText = text;
        }
        if (text == null || text.trim().isEmpty()) {
            setStatus("No saved text session yet.");
            return;
        }
        int savedPage = prefs.getInt("lastPage", 0);
        PlaybackService.Snapshot snapshot = PlaybackService.getSnapshot();
        String normalized = normalizeCopiedText(text);
        if (!autoPlay && snapshot.isActive()) {
            try {
                PlaybackDocumentStore.Document document = PlaybackDocumentStore.read(this);
                if (snapshot.documentId.equals(document.id)) {
                    fullText = normalized;
                    pages.clear();
                    pages.addAll(document.pages);
                    rebuildPlaybackProgressIndex();
                    repaginateBeforeNextPlayback = !document.pages.equals(
                            splitIntoPages(normalized, getMaxChars()));
                    currentSessionId = document.sessionId;
                    JSONObject activeMeta = currentSessionId.isEmpty()
                            ? null
                            : readJsonFile(historySessionJsonFile(currentSessionId));
                    currentSessionCreatedAt = activeMeta == null
                            ? System.currentTimeMillis()
                            : activeMeta.optLong("createdAt", System.currentTimeMillis());
                    activePlaybackDocumentId = snapshot.documentId;
                    activePlaybackTtsConfig = document.ttsConfig;
                    currentPage = snapshot.pageIndex >= 0 ? snapshot.pageIndex : savedPage;
                    currentPage = Math.max(0, Math.min(currentPage, Math.max(0, pages.size() - 1)));
                    updatePageViews();
                    setStatus("Restored the active background playback session.");
                    applyPlaybackSnapshot(snapshot, false);
                    startProgressTicker();
                    return;
                }
            } catch (Exception ignored) {
                // Fall through to ordinary session restoration.
            }
        }
        setFullTextAndPaginate(text, savedPage, autoPlay, "Loaded saved session.");
    }

    private TtsConfig getTtsConfig() {
        return TtsConfig.fromPreferences(this);
    }

    private TtsConfig getEffectiveTtsConfig() {
        PlaybackService.Snapshot snapshot = PlaybackService.getSnapshot();
        if (activePlaybackTtsConfig != null
                && !activePlaybackDocumentId.isEmpty()
                && (snapshot.documentId.isEmpty()
                        || activePlaybackDocumentId.equals(snapshot.documentId))) {
            return activePlaybackTtsConfig;
        }
        return getTtsConfig();
    }

    private boolean hasActiveOrPendingPlayback() {
        return PlaybackService.getSnapshot().isActive()
                || !activePlaybackDocumentId.isEmpty();
    }

    private boolean storedPlaybackDocumentMatchesActiveId() {
        if (activePlaybackDocumentId.isEmpty()) {
            return false;
        }
        try {
            PlaybackDocumentStore.Document document = PlaybackDocumentStore.read(this);
            return activePlaybackDocumentId.equals(document.id);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String getServerBase() {
        return normalizeServer(prefString("server", DEFAULT_SERVER_BASE));
    }

    private String normalizeServer(String value) {
        String out = value == null ? "" : value.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        if (out.isEmpty()) {
            out = DEFAULT_SERVER_BASE;
        }
        return out;
    }

    private String getModel() {
        String value = prefString("model", "kokoro").trim();
        return value.isEmpty() ? "kokoro" : value;
    }

    private String getVoice() {
        String value = prefString("voice", "af_bella").trim();
        return value.isEmpty() ? "af_bella" : value;
    }

    private float getPlaybackRate() {
        try {
            float value = Float.parseFloat(prefString("playbackRate", "1.0").trim());
            if (!Float.isFinite(value)) {
                return 1.0f;
            }
            return Math.max(0.25f, Math.min(4.0f, value));
        } catch (Exception ex) {
            return 1.0f;
        }
    }

    private String getResponseFormat() {
        return TtsConfig.normalizeResponseFormat(prefString("responseFormat", "mp3"));
    }

    private String getLangCode() {
        return prefString("langCode", "").trim();
    }

    private int getMaxChars() {
        try {
            int value = Integer.parseInt(prefString("maxChars", String.valueOf(DEFAULT_MAX_CHARS)).trim());
            return Math.max(MIN_MAX_CHARS, Math.min(SAFE_MAX_CHARS, value));
        } catch (Exception ex) {
            return DEFAULT_MAX_CHARS;
        }
    }

    private int getAutoNextDelayMs() {
        try {
            int value = Integer.parseInt(prefString("autoNextDelayMs", String.valueOf(DEFAULT_AUTO_NEXT_DELAY_MS)).trim());
            return Math.max(0, Math.min(MAX_AUTO_NEXT_DELAY_MS, value));
        } catch (Exception ex) {
            return DEFAULT_AUTO_NEXT_DELAY_MS;
        }
    }

    private String getTtsSettingsKey() {
        return getTtsConfig().settingsKey();
    }

    private String prefString(String key, String defaultValue) {
        return prefs.getString(key, defaultValue);
    }

    private boolean prefBool(String key, boolean defaultValue) {
        return prefs.getBoolean(key, defaultValue);
    }


    private int getHistoryLimit() {
        try {
            int value = Integer.parseInt(prefString("historyLimit", "20").trim());
            return Math.max(0, Math.min(200, value));
        } catch (Exception ex) {
            return 20;
        }
    }

    private boolean historyEnabled() {
        return getHistoryLimit() > 0;
    }

    private int getPrefetchPages() {
        try {
            int value = Integer.parseInt(prefString("prefetchPages", "5").trim());
            return Math.max(0, Math.min(50, value));
        } catch (Exception ex) {
            return 5;
        }
    }

    private boolean isDarkMode() {
        return prefBool("darkMode", true);
    }

    private int colorBackground() {
        return isDarkMode() ? Color.rgb(18, 18, 18) : Color.rgb(250, 250, 250);
    }

    private int colorSurface() {
        return isDarkMode() ? Color.rgb(34, 34, 34) : Color.WHITE;
    }

    private int colorText() {
        return isDarkMode() ? Color.rgb(238, 238, 238) : Color.rgb(25, 25, 25);
    }

    private int colorMutedText() {
        return isDarkMode() ? Color.rgb(180, 180, 180) : Color.rgb(85, 85, 85);
    }

    private int colorButton() {
        return isDarkMode() ? Color.rgb(58, 58, 58) : Color.rgb(232, 232, 232);
    }

    private void applyAppTheme() {
        if (rootLayout != null) {
            rootLayout.setBackgroundColor(colorBackground());
            applyThemeToTree(rootLayout);
        }
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(colorBackground());
            getWindow().setNavigationBarColor(colorBackground());
        }
    }

    private void applyThemeToTree(View view) {
        if (view == null) {
            return;
        }
        int bg = colorBackground();
        int surface = colorSurface();
        int text = colorText();
        int muted = colorMutedText();
        int button = colorButton();
        if (view instanceof CheckBox) {
            ((CheckBox) view).setTextColor(text);
        } else if (view instanceof Button) {
            view.setBackgroundColor(button);
            ((Button) view).setTextColor(text);
        } else if (view instanceof EditText) {
            view.setBackgroundColor(surface);
            ((EditText) view).setTextColor(text);
            ((EditText) view).setHintTextColor(muted);
        } else if (view instanceof TextView) {
            ((TextView) view).setTextColor(text);
        } else if (view instanceof ScrollView || view instanceof LinearLayout || view instanceof TableLayout || view instanceof TableRow) {
            view.setBackgroundColor(bg);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyThemeToTree(group.getChildAt(i));
            }
        }
    }

    private File getHistoryRootDir() {
        return new File(getFilesDir(), "kokoro_history");
    }

    private File historySessionDir(String sessionId) {
        return new File(getHistoryRootDir(), safeFileName(sessionId));
    }

    private File historySessionJsonFile(String sessionId) {
        return new File(historySessionDir(sessionId), "session.json");
    }

    private String safeFileName(String name) {
        String safe = name == null ? "" : name.replaceAll("[^A-Za-z0-9_.-]", "_");
        return safe.isEmpty() ? "session" : safe;
    }

    private void ensureCurrentHistorySession() {
        if (!historyEnabled() || fullText == null || fullText.trim().isEmpty()) {
            currentSessionId = "";
            currentSessionCreatedAt = 0L;
            return;
        }
        String id = sha256(fullText + "\n" + getTtsSettingsKey()).substring(0, 18);
        if (!id.equals(currentSessionId)) {
            currentSessionId = id;
            File json = historySessionJsonFile(id);
            long now = System.currentTimeMillis();
            if (json.exists()) {
                JSONObject meta = readJsonFile(json);
                currentSessionCreatedAt = meta == null ? now : meta.optLong("createdAt", now);
            } else {
                currentSessionCreatedAt = now;
            }
        }
        persistCurrentHistoryMetadata();
    }

    private JSONObject currentSettingsSnapshot() throws Exception {
        TtsConfig config = getEffectiveTtsConfig();
        JSONObject settings = new JSONObject();
        settings.put("server", config.serverBase);
        settings.put("model", config.model);
        settings.put("voice", config.voice);
        settings.put("speed", String.valueOf(config.speed));
        settings.put("playbackRate", String.valueOf(getPlaybackRate()));
        settings.put("responseFormat", config.responseFormat);
        settings.put("stream", config.stream);
        settings.put("langCode", config.langCode);
        settings.put("maxChars", String.valueOf(getMaxChars()));
        settings.put("autoNextDelayMs", String.valueOf(getAutoNextDelayMs()));
        settings.put("normalize", config.normalize);
        settings.put("unitNorm", config.unitNormalization);
        settings.put("urlNorm", config.urlNormalization);
        settings.put("emailNorm", config.emailNormalization);
        settings.put("pluralNorm", config.pluralNormalization);
        settings.put("phoneNorm", config.phoneNormalization);
        return settings;
    }

    private void applySettingsSnapshot(JSONObject settings) {
        if (settings == null) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        String[] stringKeys = new String[]{"server", "model", "voice", "speed", "playbackRate", "responseFormat", "langCode", "maxChars", "autoNextDelayMs"};
        for (String key : stringKeys) {
            if (settings.has(key)) {
                editor.putString(key, settings.optString(key, prefString(key, "")));
            }
        }
        String[] boolKeys = new String[]{"stream", "normalize", "unitNorm", "urlNorm", "emailNorm", "pluralNorm", "phoneNorm"};
        for (String key : boolKeys) {
            if (settings.has(key)) {
                editor.putBoolean(key, settings.optBoolean(key, prefBool(key, false)));
            }
        }
        editor.apply();
    }

    private void persistCurrentHistoryMetadata() {
        if (!historyEnabled() || currentSessionId == null || currentSessionId.trim().isEmpty()
                || fullText == null || fullText.trim().isEmpty()) {
            return;
        }
        try {
            File dir = historySessionDir(currentSessionId);
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File jsonFile = new File(dir, "session.json");
            long now = System.currentTimeMillis();
            JSONObject settingsForNewSession = currentSettingsSnapshot();
            int audioCount = countAudioFilesInSession(currentSessionId);
            AtomicFileStore.mutateJson(jsonFile, meta -> {
                long createdAt = meta.optLong("createdAt", currentSessionCreatedAt > 0L
                        ? currentSessionCreatedAt
                        : now);
                JSONObject frozenSettings = meta.optJSONObject("settings");
                if (frozenSettings == null) {
                    frozenSettings = settingsForNewSession;
                }
                meta.put("id", currentSessionId);
                meta.put("createdAt", createdAt);
                meta.put("updatedAt", now);
                meta.put("currentPage", currentPage);
                meta.put("pageCount", pages.size());
                meta.put("charCount", fullText.length());
                meta.put("title", titleForText(fullText));
                meta.put("text", fullText);
                meta.put("settings", frozenSettings);
                meta.put("audioCount", audioCount);
            });
            JSONObject persisted = readJsonFile(jsonFile);
            if (persisted != null) {
                currentSessionCreatedAt = persisted.optLong("createdAt", now);
            }
            pruneHistoryToLimit();
        } catch (Exception ignored) {
        }
    }

    private String titleForText(String text) {
        if (text == null) {
            return "Untitled";
        }
        String oneLine = text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (oneLine.isEmpty()) {
            return "Untitled";
        }
        return oneLine.length() > 96 ? oneLine.substring(0, 96) + "..." : oneLine;
    }

    private File findPlayableAudioFile(int pageIndex, String text) {
        return findPlayableAudioFile(currentSessionId, pageIndex, text);
    }

    private File findPlayableAudioFile(String sessionId, int pageIndex, String text) {
        return KokoroAudioRepository.findPlayable(
                this, getEffectiveTtsConfig(), getHistoryLimit(), sessionId, pageIndex, text);
    }

    private boolean audioAvailableForPage(int pageIndex, String text) {
        File f = findPlayableAudioFile(pageIndex, text);
        return f != null && f.exists() && f.length() > 0;
    }

    private String getTrackBuildStatusLabel() {
        if (pages.isEmpty()) {
            return "Track: no text";
        }
        int have = 0;
        for (int i = 0; i < pages.size(); i++) {
            if (audioAvailableForPage(i, pages.get(i))) {
                have++;
            }
        }
        boolean fetching = getActiveGenerationCount() > 0 || PlaybackService.getSnapshot().generationActive;
        if (have >= pages.size()) {
            return "Track: complete/offline";
        }
        return String.format(Locale.US, "Track: %s (%d/%d ready)", fetching ? "fetching" : "partial", have, pages.size());
    }

    private int getActiveGenerationCount() {
        synchronized (activeAudioGenerations) {
            return activeAudioGenerations.size();
        }
    }

    private boolean markAudioGenerationStarted(String key) {
        synchronized (activeAudioGenerations) {
            if (activeAudioGenerations.contains(key)) {
                return false;
            }
            activeAudioGenerations.add(key);
            mainHandler.post(this::updatePageViews);
            return true;
        }
    }

    private void markAudioGenerationFinished(String key) {
        synchronized (activeAudioGenerations) {
            activeAudioGenerations.remove(key);
        }
        mainHandler.post(this::updatePageViews);
    }

    private void ensurePrefetchAhead(int fromPageIndex, boolean announce) {
        if (hasActiveOrPendingPlayback()) {
            return;
        }
        if (pages.isEmpty()) {
            return;
        }
        int count = getPrefetchPages();
        if (count <= 0) {
            return;
        }
        int start = Math.max(0, fromPageIndex) + 1;
        if (start >= pages.size()) {
            updatePageViews();
            return;
        }
        pregeneratePagesFrom(start, count, announce);
    }

    private ArrayList<JSONObject> readHistorySessions() {
        ArrayList<JSONObject> sessions = new ArrayList<>();
        File root = getHistoryRootDir();
        File[] dirs = root.listFiles();
        if (dirs == null) {
            return sessions;
        }
        for (File dir : dirs) {
            if (!dir.isDirectory()) {
                continue;
            }
            JSONObject meta = readJsonFile(new File(dir, "session.json"));
            if (meta != null) {
                sessions.add(meta);
            }
        }
        sessions.sort((a, b) -> Long.compare(b.optLong("updatedAt", 0L), a.optLong("updatedAt", 0L)));
        return sessions;
    }

    private void pruneHistoryToLimit() {
        int limit = getHistoryLimit();
        File root = getHistoryRootDir();
        if (limit <= 0) {
            AtomicFileStore.deleteTree(root);
            currentSessionId = "";
            currentSessionCreatedAt = 0L;
            return;
        }
        ArrayList<JSONObject> sessions = readHistorySessions();
        for (int i = limit; i < sessions.size(); i++) {
            String id = sessions.get(i).optString("id", "");
            if (!id.isEmpty() && !id.equals(currentSessionId)) {
                AtomicFileStore.deleteTree(historySessionDir(id));
            }
        }
    }

    private void showHistoryDialog() {
        if (!historyEnabled()) {
            setStatus("Speech history is disabled. Increase 'History sessions to keep' in Settings to enable it.");
            toast("History disabled");
            return;
        }
        ArrayList<JSONObject> sessions = readHistorySessions();
        if (sessions.isEmpty()) {
            setStatus("No generated speech history yet.");
            toast("No history yet");
            return;
        }
        ScrollView scroll = new ScrollView(this);
        TableLayout table = new TableLayout(this);
        table.setStretchAllColumns(false);
        table.setShrinkAllColumns(true);
        int pad = dp(8);
        table.setPadding(pad, pad, pad, pad);
        scroll.addView(table);

        final AlertDialog[] historyDialogRef = new AlertDialog[1];

        TableRow header = new TableRow(this);
        addTableCell(header, "When", true, 0);
        addTableCell(header, "Audio", true, 0);
        addTableCell(header, "Voice", true, 0);
        addTableCell(header, "Text", true, 1);
        table.addView(header);

        for (JSONObject meta : sessions) {
            TableRow row = new TableRow(this);
            row.setPadding(0, dp(4), 0, dp(4));
            final String id = meta.optString("id", "");
            int pageCount = meta.optInt("pageCount", 0);
            String text = meta.optString("text", "");
            JSONObject settings = meta.optJSONObject("settings");
            String voice = settings == null ? "" : settings.optString("voice", "");
            int audioCount = countAudioFilesInSession(id);
            addTableCell(row, formatDateShort(meta.optLong("updatedAt", 0L)), false, 0);
            addTableCell(row, audioCount + "/" + pageCount, false, 0);
            addTableCell(row, voice, false, 0);
            addTableCell(row, meta.optString("title", titleForText(text)), false, 1);
            row.setOnClickListener(v -> {
                if (historyDialogRef[0] != null) {
                    historyDialogRef[0].dismiss();
                }
                loadHistorySession(id, true);
            });
            table.addView(row);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Speech History")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();
        historyDialogRef[0] = dialog;
        dialog.setOnShowListener(d -> applyThemeToTree(scroll));
        dialog.show();
    }

    private void addTableCell(TableRow row, String text, boolean header, int weight) {
        TextView cell = new TextView(this);
        cell.setText(text == null ? "" : text);
        cell.setTextSize(header ? 14 : 13);
        cell.setPadding(dp(6), dp(4), dp(6), dp(4));
        cell.setMaxLines(header ? 1 : 2);
        cell.setTextColor(colorText());
        TableRow.LayoutParams params = new TableRow.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight <= 0 ? 0.0f : (float) weight);
        if (weight <= 0) {
            params = new TableRow.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        row.addView(cell, params);
    }

    private void loadHistorySession(String sessionId, boolean autoPlay) {
        JSONObject meta = readJsonFile(historySessionJsonFile(sessionId));
        if (meta == null) {
            setStatus("Could not load history session " + sessionId + ".");
            return;
        }
        releasePlayer();
        applySettingsSnapshot(meta.optJSONObject("settings"));
        String text = meta.optString("text", "");
        currentSessionId = meta.optString("id", sessionId);
        currentSessionCreatedAt = meta.optLong("createdAt", System.currentTimeMillis());
        int page = meta.optInt("currentPage", 0);
        setFullTextAndPaginate(text, page, autoPlay, "Loaded history session.");
    }

    private int countAudioFilesInSession(String sessionId) {
        return countAudioFilesInDir(historySessionDir(sessionId));
    }

    private int countAudioFilesInDir(File dir) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (File f : files) {
            if (f.isDirectory()) {
                count += countAudioFilesInDir(f);
                continue;
            }
            String name = f.getName().toLowerCase(Locale.US);
            if (f.isFile() && (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".aac") || name.endsWith(".flac") || name.endsWith(".opus"))) {
                count++;
            }
        }
        return count;
    }

    private JSONObject readJsonFile(File file) {
        try {
            return file == null ? null : AtomicFileStore.readJson(file);
        } catch (Exception ex) {
            return null;
        }
    }

    private String formatDateShort(long timeMs) {
        if (timeMs <= 0L) {
            return "unknown";
        }
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(timeMs));
    }

    private String getBuildInfoText() {
        return "App version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n"
                + "Application ID: " + BuildConfig.APPLICATION_ID + "\n"
                + "Build type: " + BuildConfig.BUILD_TYPE + "\n"
                + "Git SHA: " + BuildConfig.GIT_SHA + "\n"
                + "Git commit date: " + BuildConfig.GIT_COMMIT_TIME_UTC + "\n"
                + "Git branch: " + BuildConfig.GIT_BRANCH + "\n"
                + "Git describe: " + BuildConfig.GIT_DESCRIBE + "\n"
                + "Git tree state: " + BuildConfig.GIT_TREE_STATE;
    }

    private void addBuildInfoSection(LinearLayout parent) {
        TextView heading = new TextView(this);
        heading.setText("Build information");
        heading.setTextSize(18);
        heading.setPadding(0, dp(18), 0, dp(4));
        parent.addView(heading, matchWrap());

        TextView info = new TextView(this);
        info.setText(getBuildInfoText());
        info.setTextSize(13);
        info.setTextIsSelectable(true);
        info.setTypeface(android.graphics.Typeface.MONOSPACE);
        info.setPadding(dp(8), dp(8), dp(8), dp(8));
        parent.addView(info, matchWrap());

        LinearLayout buildButtons = row(parent);
        Button copyBuildInfoButton = addDialogButton(buildButtons, "Copy Build Info", 1);
        copyBuildInfoButton.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                toast("Clipboard is not available.");
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("Kokoro Reader build info", getBuildInfoText()));
            toast("Build info copied.");
        });
    }

    private void showSettingsDialog() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Kokoro Reader Settings");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, matchWrap());

        addLabel(root, "Server URL");
        EditText serverEdit = addDialogEdit(root, getServerBase(), true, 1);
        serverEdit.setInputType(InputType.TYPE_TEXT_VARIATION_URI);

        LinearLayout row1 = row(root);
        LinearLayout modelCol = col(row1, 1);
        addLabel(modelCol, "Model");
        EditText modelEdit = addDialogEdit(modelCol, getModel(), true, 1);
        LinearLayout voiceCol = col(row1, 2);
        addLabel(voiceCol, "Voice / mix");
        EditText voiceEdit = addDialogEdit(voiceCol, getVoice(), true, 1);

        LinearLayout voiceButtons = row(root);
        Button healthButton = addDialogButton(voiceButtons, "Health Check", 1);
        Button fetchVoicesButton = addDialogButton(voiceButtons, "Fetch Voices", 1);
        Button clearCacheButton = addDialogButton(voiceButtons, "Clear Audio Cache", 1);

        LinearLayout row2 = row(root);
        LinearLayout speedCol = col(row2, 1);
        addLabel(speedCol, "TTS speed");
        EditText speedEdit = addDialogEdit(speedCol, prefString("speed", "1.0"), true, 1);
        speedEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        LinearLayout playbackCol = col(row2, 1);
        addLabel(playbackCol, "Phone playback rate");
        EditText playbackRateEdit = addDialogEdit(playbackCol, prefString("playbackRate", "1.0"), true, 1);
        playbackRateEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);

        LinearLayout row3 = row(root);
        LinearLayout formatCol = col(row3, 1);
        addLabel(formatCol, "Format (mp3, opus, aac, flac, wav)");
        EditText formatEdit = addDialogEdit(formatCol, getResponseFormat(), true, 1);
        LinearLayout langCol = col(row3, 1);
        addLabel(langCol, "Language code, blank = auto");
        EditText langCodeEdit = addDialogEdit(langCol, getLangCode(), true, 1);

        LinearLayout row4 = row(root);
        LinearLayout charsCol = col(row4, 1);
        addLabel(charsCol, "Chars per page/chunk (safe cap " + SAFE_MAX_CHARS + ")");
        EditText maxCharsEdit = addDialogEdit(charsCol, String.valueOf(getMaxChars()), true, 1);
        maxCharsEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout historyCol = col(row4, 1);
        addLabel(historyCol, "History sessions to keep");
        EditText historyLimitEdit = addDialogEdit(historyCol, prefString("historyLimit", "20"), true, 1);
        historyLimitEdit.setInputType(InputType.TYPE_CLASS_NUMBER);

        CheckBox darkModeBox = addDialogCheck(root, "Dark mode", prefBool("darkMode", true));
        CheckBox streamBox = addDialogCheck(root, "Ask server to stream response", prefBool("stream", true));
        CheckBox autoNextBox = addDialogCheck(root, "Auto-generate/play next page", prefBool("autoNext", true));
        addLabel(root, "Pause before auto-next, milliseconds");
        EditText autoNextDelayEdit = addDialogEdit(root, String.valueOf(getAutoNextDelayMs()), true, 1);
        autoNextDelayEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        addLabel(root, "Pages to prefetch ahead while playing");
        EditText prefetchEdit = addDialogEdit(root, prefString("prefetchPages", "5"), true, 1);
        prefetchEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        CheckBox wholeTextProgressBox = addDialogCheck(root, "Playback bar tracks entire text", prefBool("wholeTextProgress", true));
        CheckBox autoRestoreBox = addDialogCheck(root, "Auto-load last text/session at startup", prefBool("autoRestore", true));

        addLabel(root, "Kokoro normalization options");
        CheckBox normalizeBox = addDialogCheck(root, "normalize", prefBool("normalize", true));
        CheckBox unitNormBox = addDialogCheck(root, "unit normalization", prefBool("unitNorm", false));
        CheckBox urlNormBox = addDialogCheck(root, "URL normalization", prefBool("urlNorm", true));
        CheckBox emailNormBox = addDialogCheck(root, "email normalization", prefBool("emailNorm", true));
        CheckBox pluralNormBox = addDialogCheck(root, "optional pluralization normalization", prefBool("pluralNorm", true));
        CheckBox phoneNormBox = addDialogCheck(root, "phone normalization", prefBool("phoneNorm", true));

        LinearLayout sessionButtons = row(root);
        Button loadButton = addDialogButton(sessionButtons, "Load Last Session", 1);
        Button historyDialogButton = addDialogButton(sessionButtons, "History", 1);
        LinearLayout maintenanceButtons = row(root);
        Button pregenerateButton = addDialogButton(maintenanceButtons, "Pre-gen Next 3", 1);
        Button clearHistoryButton = addDialogButton(maintenanceButtons, "Clear History", 1);

        addBuildInfoSection(root);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(scroll)
                .setPositiveButton("Save", null)
                .setNegativeButton("Close", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                cancelActivityGenerationWork(true);
                int previousMaxChars = getMaxChars();
                prefs.edit()
                        .putString("server", normalizeServer(serverEdit.getText().toString()))
                        .putString("model", modelEdit.getText().toString().trim())
                        .putString("voice", voiceEdit.getText().toString().trim())
                        .putString("speed", speedEdit.getText().toString().trim())
                        .putString("playbackRate", playbackRateEdit.getText().toString().trim())
                        .putString("responseFormat", TtsConfig.normalizeResponseFormat(formatEdit.getText().toString()))
                        .putString("langCode", langCodeEdit.getText().toString().trim())
                        .putString("maxChars", maxCharsEdit.getText().toString().trim())
                        .putString("historyLimit", historyLimitEdit.getText().toString().trim())
                        .putString("autoNextDelayMs", autoNextDelayEdit.getText().toString().trim())
                        .putString("prefetchPages", prefetchEdit.getText().toString().trim())
                        .putBoolean("darkMode", darkModeBox.isChecked())
                        .putBoolean("stream", streamBox.isChecked())
                        .putBoolean("autoNext", autoNextBox.isChecked())
                        .putBoolean("wholeTextProgress", wholeTextProgressBox.isChecked())
                        .putBoolean("autoRestore", autoRestoreBox.isChecked())
                        .putBoolean("normalize", normalizeBox.isChecked())
                        .putBoolean("unitNorm", unitNormBox.isChecked())
                        .putBoolean("urlNorm", urlNormBox.isChecked())
                        .putBoolean("emailNorm", emailNormBox.isChecked())
                        .putBoolean("pluralNorm", pluralNormBox.isChecked())
                        .putBoolean("phoneNorm", phoneNormBox.isChecked())
                        .apply();
                boolean playbackActive = hasActiveOrPendingPlayback();
                boolean pageSizeChanged = previousMaxChars != getMaxChars();
                if (playbackActive && pageSizeChanged) {
                    repaginateBeforeNextPlayback = true;
                }
                if (!playbackActive && !fullText.trim().isEmpty()) {
                    int keepPage = currentPage;
                    pages.clear();
                    pages.addAll(splitIntoPages(fullText, getMaxChars()));
                    rebuildPlaybackProgressIndex();
                    currentPage = Math.max(0, Math.min(keepPage, Math.max(0, pages.size() - 1)));
                    repaginateBeforeNextPlayback = false;
                }
                if (!playbackActive && historyEnabled() && !fullText.trim().isEmpty()) {
                    ensureCurrentHistorySession();
                }
                updatePageViews();
                applyAppTheme();
                pruneHistoryToLimit();
                ensurePrefetchAhead(currentPage, false);
                if (playbackSeek != null && !playbackSeekUserTouch) {
                    playbackSeek.setMax(getPlaybackBarMax());
                    if (!PlaybackService.getSnapshot().prepared) {
                        playbackSeek.setProgress(getPlaybackBarStartForPage(currentPage));
                    }
                }
                updatePlaybackProgress();
                saveSession();
                setStatus(playbackActive
                        ? "Settings saved. TTS and pagination changes apply when you start a new playback run."
                        : "Settings saved.");
                dialog.dismiss();
            });
        });

        healthButton.setOnClickListener(v -> healthCheckServer(serverEdit));
        fetchVoicesButton.setOnClickListener(v -> fetchVoicesIntoField(serverEdit, voiceEdit));
        clearCacheButton.setOnClickListener(v -> clearAudioCache());
        loadButton.setOnClickListener(v -> restoreLastSession(false));
        historyDialogButton.setOnClickListener(v -> showHistoryDialog());
        pregenerateButton.setOnClickListener(v -> pregenerateNextPages(3, true));
        clearHistoryButton.setOnClickListener(v -> showClearHistoryDialog());

        dialog.show();
        applyThemeToTree(scroll);
    }

    private void healthCheckServer(EditText serverEdit) {
        String base = normalizeServer(serverEdit.getText().toString());
        setStatus("Checking Kokoro health at " + base + "...");
        executor.submit(() -> {
            String[] paths = new String[]{"/health", "/v1/audio/voices"};
            String lastError = "no response";
            for (String path : paths) {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) URI.create(base + path).toURL().openConnection();
                    activityUtilityNetworkScope.register(conn);
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("Accept", "application/json, text/plain, */*");
                    int code = conn.getResponseCode();
                    String body;
                    try (InputStream is = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream()) {
                        body = is == null ? "" : readAllLimited(is, 512).trim();
                    }
                    if (code >= 200 && code < 300) {
                        String snippet = body.isEmpty() ? "OK" : body.replace('\n', ' ');
                        if (snippet.length() > 160) {
                            snippet = snippet.substring(0, 160) + "...";
                        }
                        final String message = "Health OK: HTTP " + code + " " + path + " - " + snippet;
                        postIfActivityAlive(() -> {
                            setStatus(message);
                            toast("Kokoro health check OK");
                        });
                        return;
                    }
                    lastError = "HTTP " + code + " " + path + ": " + body;
                } catch (Exception ex) {
                    lastError = path + ": " + ex.getMessage();
                } finally {
                    if (conn != null) {
                        activityUtilityNetworkScope.unregister(conn);
                        conn.disconnect();
                    }
                }
            }
            final String message = "Health check failed: " + lastError;
            postIfActivityAlive(() -> {
                setStatus(message);
                toast("Kokoro health check failed");
            });
        });
    }

    private void fetchVoicesIntoField(EditText serverEdit, EditText voiceEdit) {
        String base = normalizeServer(serverEdit.getText().toString());
        setStatus("Fetching voices from " + base + "...");
        executor.submit(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) URI.create(base + "/v1/audio/voices").toURL().openConnection();
                activityUtilityNetworkScope.register(conn);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("Accept", "application/json");
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("HTTP " + code + ": " + readError(conn));
                }
                String body;
                try (InputStream is = conn.getInputStream()) {
                    body = readAll(is);
                }
                ArrayList<String> voices = parseVoiceList(body);
                postIfActivityAlive(() -> showVoiceDialogForField(voices, voiceEdit));
            } catch (InterruptedException ignored) {
                // Activity teardown cancels utility requests without surfacing a stale error.
            } catch (Exception ex) {
                postIfActivityAlive(() -> setStatus("Could not fetch voices: " + ex.getMessage()));
            } finally {
                if (conn != null) {
                    activityUtilityNetworkScope.unregister(conn);
                    conn.disconnect();
                }
            }
        });
    }

    private void postIfActivityAlive(Runnable action) {
        mainHandler.post(() -> {
            if (!destroyed && activityStarted && !isFinishing()) {
                action.run();
            }
        });
    }

    private String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = is.read(buffer)) != -1) {
            baos.write(buffer, 0, n);
        }
        return baos.toString("UTF-8");
    }

    private String readAllLimited(InputStream is, int limit) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int n;
        while ((n = is.read(buffer)) != -1 && baos.size() < limit) {
            int remaining = limit - baos.size();
            baos.write(buffer, 0, Math.min(n, remaining));
        }
        return baos.toString("UTF-8");
    }

    private ArrayList<String> parseVoiceList(String body) throws Exception {
        ArrayList<String> voices = new ArrayList<>();
        JSONObject obj = new JSONObject(body);
        JSONArray arr = obj.optJSONArray("voices");
        if (arr == null) {
            arr = obj.optJSONArray("data");
        }
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.get(i);
                if (item instanceof String) {
                    voices.add((String) item);
                } else if (item instanceof JSONObject) {
                    JSONObject v = (JSONObject) item;
                    String id = v.optString("id", v.optString("name", ""));
                    if (!id.isEmpty()) {
                        voices.add(id);
                    }
                }
            }
        }
        if (voices.isEmpty()) {
            throw new IllegalStateException("No voices found in response: " + body);
        }
        return voices;
    }

    private void showVoiceDialogForField(ArrayList<String> voices, EditText voiceEdit) {
        String[] items = voices.toArray(new String[0]);
        setStatus("Fetched " + items.length + " voices.");
        new AlertDialog.Builder(this)
                .setTitle("Choose voice")
                .setItems(items, (dialog, which) -> {
                    voiceEdit.setText(items[which]);
                    setStatus("Selected voice " + items[which] + ". Tap Save in settings to keep it.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));
        parent.addView(row, matchWrap());
        return row;
    }

    private LinearLayout col(LinearLayout parent, int weight) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        params.setMargins(dp(2), 0, dp(2), 0);
        parent.addView(col, params);
        return col;
    }

    private void addLabel(LinearLayout parent, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(13);
        label.setPadding(0, dp(8), 0, dp(2));
        parent.addView(label, matchWrap());
    }

    private EditText addDialogEdit(LinearLayout parent, String value, boolean singleLine, int minLines) {
        EditText edit = new EditText(this);
        edit.setText(value);
        edit.setSingleLine(singleLine);
        edit.setMinLines(minLines);
        edit.setTextSize(16);
        edit.setSelectAllOnFocus(false);
        parent.addView(edit, matchWrap());
        return edit;
    }

    private CheckBox addDialogCheck(LinearLayout parent, String text, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextSize(15);
        box.setChecked(checked);
        parent.addView(box, matchWrap());
        return box;
    }

    private Button addDialogButton(LinearLayout parent, String text, int weight) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setMinHeight(dp(48));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        params.setMargins(dp(3), dp(2), dp(3), dp(2));
        parent.addView(button, params);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setStatus(String message) {
        if (statusView != null) {
            statusView.setText(message);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
