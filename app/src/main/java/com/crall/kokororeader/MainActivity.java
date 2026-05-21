package com.crall.kokororeader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.graphics.Color;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "kokoro_reader_prefs";
    private static final String DEFAULT_SERVER_BASE = "http://10.0.2.2:8880";
    private static final int PAGE_PROGRESS_MAX = 1000;

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
    private int currentPage = 0;
    private int totalPlaybackUnits = 1;
    private boolean programmaticTextUpdate = false;

    private MediaPlayer player;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private boolean playbackPrepared = false;
    private boolean playbackSeekUserTouch = false;
    private boolean playbackGenerationActive = false;
    private boolean playbackProgressIsEstimate = false;
    private int preparedPageIndex = -1;
    private int activePlaybackPageIndex = -1;
    private long estimatedPlaybackStartedAt = 0L;
    private long estimatedBytesRead = 0L;
    private long estimatedBytesExpected = -1L;
    private long lastProgressReportAt = 0L;
    private int pendingSeekPageIndex = -1;
    private int pendingSeekPageOffsetUnits = -1;
    private String currentSessionId = "";
    private long currentSessionCreatedAt = 0L;

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
    protected void onPause() {
        super.onPause();
        saveSession();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayer();
        executor.shutdownNow();
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
        navRow.addView(prevButton, new LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));

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
        navRow.addView(nextButton, new LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));

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
        textEdit.setMinLines(8);
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
        playTextButton.setText("Play all");
        playTextButton.setAllCaps(false);
        playTextButton.setTextSize(22);
        playTextButton.setMinHeight(dp(96));
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.setMargins(dp(6), 0, 0, 0);
        buttonRow.addView(playTextButton, textParams);

        historyButton.setOnClickListener(v -> showHistoryDialog());
        settingsButton.setOnClickListener(v -> showSettingsDialog());
        prevButton.setOnClickListener(v -> movePage(-1));
        nextButton.setOnClickListener(v -> movePage(1));
        playClipboardButton.setOnClickListener(v -> readClipboardSplitGeneratePlay());
        playTextButton.setOnClickListener(v -> playAllFromCurrentPage());
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
        applyThemeToTree(root);
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
        if (!ensurePagesFromTextBox()) {
            return;
        }
        clearPendingDocumentSeek();
        generateAndPlayCurrentPage();
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
        releasePlayer();
        String normalized = normalizeCopiedText(raw);
        fullText = normalized;
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
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        text = text.replace((char) 160, ' ');
        text = text.replace('\t', ' ');
        text = text.replace('\f', ' ');
        text = text.replace((char) 11, ' ');
        text = text.replaceAll("(?m)-\\n(?=\\p{Ll})", "");
        text = text.trim();
        if (text.isEmpty()) {
            return "";
        }

        String[] paragraphs = text.split("\\n\\s*\\n+");
        StringBuilder out = new StringBuilder();
        for (String paragraph : paragraphs) {
            String p = paragraph.replaceAll("\\s*\\n\\s*", " ").replaceAll(" {2,}", " ").trim();
            if (!p.isEmpty()) {
                if (out.length() > 0) {
                    out.append("\n\n");
                }
                out.append(p);
            }
        }
        return out.toString();
    }

    private ArrayList<String> splitIntoPages(String text, int maxChars) {
        ArrayList<String> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return result;
        }
        String[] paragraphs = text.split("\\n\\n+");
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            String p = paragraph.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() > maxChars) {
                flushPage(result, current);
                result.addAll(splitLongParagraph(p, maxChars));
            } else if (current.length() > 0 && current.length() + p.length() + 2 > maxChars) {
                flushPage(result, current);
                current.append(p);
            } else {
                if (current.length() > 0) {
                    current.append("\n\n");
                }
                current.append(p);
            }
        }
        flushPage(result, current);
        return result;
    }

    private void flushPage(ArrayList<String> result, StringBuilder current) {
        String page = current.toString().trim();
        if (!page.isEmpty()) {
            result.add(page);
        }
        current.setLength(0);
    }

    private ArrayList<String> splitLongParagraph(String paragraph, int maxChars) {
        ArrayList<String> result = new ArrayList<>();
        String[] sentences = paragraph.split("(?<=[.!?])\\s+");
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            String s = sentence.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (s.length() > maxChars) {
                flushPage(result, current);
                hardSplit(result, s, maxChars);
            } else if (current.length() > 0 && current.length() + s.length() + 1 > maxChars) {
                flushPage(result, current);
                current.append(s);
            } else {
                if (current.length() > 0) {
                    current.append(' ');
                }
                current.append(s);
            }
        }
        flushPage(result, current);
        return result;
    }

    private void hardSplit(ArrayList<String> result, String text, int maxChars) {
        int start = 0;
        int length = text.length();
        while (start < length) {
            int end = Math.min(start + maxChars, length);
            if (end < length) {
                int space = text.lastIndexOf(' ', end);
                if (space > start + maxChars / 2) {
                    end = space;
                }
            }
            String piece = text.substring(start, end).trim();
            if (!piece.isEmpty()) {
                result.add(piece);
            }
            start = Math.max(end, start + 1);
            while (start < length && Character.isWhitespace(text.charAt(start))) {
                start++;
            }
        }
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
        saveSession();
        final int pageIndex = currentPage;
        final String text = pages.get(pageIndex);
        final File cached = audioFileFor(pageIndex, text);
        File playable = findPlayableAudioFile(pageIndex, text);
        if (playable != null && playable.exists() && playable.length() > 0) {
            setStatus((playable.equals(cached) ? "Using cached" : "Using saved history") + " audio for page " + (pageIndex + 1) + ".");
            playAudio(playable, pageIndex);
            updatePageViews();
            ensurePrefetchAhead(pageIndex, false);
            return;
        }
        if (playbackGenerationActive && activePlaybackPageIndex == pageIndex) {
            setStatus("Already generating page " + (pageIndex + 1) + ".");
            return;
        }

        setEstimatedPlaybackProgress(pageIndex, "Preparing page " + (pageIndex + 1) + "... waiting for Kokoro stream");
        setStatus("Generating page " + (pageIndex + 1) + " of " + pages.size() + "...");
        final String sessionId = currentSessionId;
        executor.submit(() -> {
            try {
                File out = requestSpeech(text, pageIndex, sessionId);
                mainHandler.post(() -> {
                    if (activePlaybackPageIndex == pageIndex) {
                        playbackGenerationActive = false;
                    }
                    updatePageViews();
                    if (currentPage == pageIndex) {
                        setStatus("Generated page " + (pageIndex + 1) + ". Playing.");
                        playAudio(out, pageIndex);
                        ensurePrefetchAhead(pageIndex, false);
                    } else {
                        setStatus("Generated page " + (pageIndex + 1) + ".");
                    }
                });
            } catch (Exception ex) {
                mainHandler.post(() -> {
                    if (activePlaybackPageIndex == pageIndex) {
                        playbackGenerationActive = false;
                        playbackProgressIsEstimate = false;
                        activePlaybackPageIndex = -1;
                        resetPlaybackControls("Playback: generation failed");
                    }
                    setStatus("Generation failed: " + ex.getMessage());
                });
            }
        });
    }

    private void pregenerateNextPages(int count, boolean announce) {
        if (pages.isEmpty()) {
            return;
        }
        final int start = currentPage + 1;
        final int end = Math.min(pages.size(), start + count);
        final String sessionId = currentSessionId;
        if (start >= end) {
            return;
        }
        if (announce) {
            setStatus("Pre-generating pages " + (start + 1) + " through " + end + "...");
        }
        executor.submit(() -> {
            int generated = 0;
            for (int i = start; i < end; i++) {
                try {
                    if (!audioAvailableForPage(i, pages.get(i))) {
                        requestSpeech(pages.get(i), i, sessionId);
                        generated++;
                    }
                } catch (Exception ex) {
                    if (announce) {
                        final int failedPage = i + 1;
                        mainHandler.post(() -> setStatus("Pre-generation failed on page " + failedPage + ": " + ex.getMessage()));
                    }
                    return;
                }
            }
            final int finalGenerated = generated;
            mainHandler.post(() -> {
                updatePageViews();
                if (announce) {
                    setStatus("Pre-generation done. New pages generated: " + finalGenerated + ".");
                }
            });
        });
    }

    private File requestSpeech(String text, int pageIndex, String sessionId) throws Exception {
        File cached = audioFileFor(pageIndex, text);
        File existing = findPlayableAudioFile(sessionId, pageIndex, text);
        if (existing != null && existing.exists() && existing.length() > 0) {
            return existing;
        }
        String generationKey = cached.getAbsolutePath();
        if (!markAudioGenerationStarted(generationKey)) {
            File waited = waitForGeneratedAudio(sessionId, pageIndex, text, 180000L);
            if (waited != null) {
                return waited;
            }
            throw new IllegalStateException("Timed out waiting for another generation of page " + (pageIndex + 1) + ".");
        }
        try {
            File dir = cached.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("Could not create cache directory: " + dir.getAbsolutePath());
            }

            URL url = new URL(getServerBase() + "/v1/audio/speech");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(180000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", acceptHeaderForFormat(getResponseFormat()));

            JSONObject payload = new JSONObject();
            payload.put("model", getModel());
            payload.put("input", text);
            payload.put("voice", getVoice());
            payload.put("response_format", getResponseFormat());
            payload.put("speed", getTtsSpeed());
            payload.put("stream", prefBool("stream", true));
            String lang = getLangCode();
            if (!lang.isEmpty()) {
                payload.put("lang_code", lang);
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
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String error = readError(conn);
                throw new IllegalStateException("HTTP " + code + " from Kokoro: " + error);
            }

            File tmp = new File(cached.getAbsolutePath() + ".tmp");
            long expectedBytes = conn.getContentLengthLong();
            try (InputStream is = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buffer = new byte[8192];
                int n;
                long totalRead = 0L;
                long lastReportedBytes = 0L;
                reportDownloadProgress(pageIndex, totalRead, expectedBytes);
                while ((n = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, n);
                    totalRead += n;
                    long now = System.currentTimeMillis();
                    if (totalRead - lastReportedBytes >= 65536 || now - lastProgressReportAt >= 750) {
                        lastReportedBytes = totalRead;
                        lastProgressReportAt = now;
                        reportDownloadProgress(pageIndex, totalRead, expectedBytes);
                    }
                }
                reportDownloadProgress(pageIndex, totalRead, totalRead);
            } finally {
                conn.disconnect();
            }

            if (tmp.length() == 0) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new IllegalStateException("Kokoro returned an empty audio file.");
            }
            if (cached.exists()) {
                //noinspection ResultOfMethodCallIgnored
                cached.delete();
            }
            if (!tmp.renameTo(cached)) {
                throw new IllegalStateException("Could not move generated audio into cache.");
            }
            persistGeneratedAudio(sessionId, pageIndex, text, cached);
            return cached;
        } finally {
            markAudioGenerationFinished(generationKey);
        }
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
            case "mp3":
            default:
                return "audio/mpeg, audio/*, application/octet-stream";
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
        playbackGenerationActive = true;
        playbackProgressIsEstimate = true;
        playbackPrepared = false;
        activePlaybackPageIndex = pageIndex;
        preparedPageIndex = -1;
        estimatedPlaybackStartedAt = System.currentTimeMillis();
        estimatedBytesRead = 0L;
        estimatedBytesExpected = -1L;
        lastProgressReportAt = 0L;
        if (playPauseButton != null) {
            playPauseButton.setText("Play");
        }
        if (playbackSeek != null) {
            playbackSeek.setMax(getPlaybackBarMax());
            playbackSeek.setProgress(getPlaybackBarStartForPage(pageIndex));
        }
        setPlaybackLabel(message + " (" + getPlaybackScopeName() + " estimate)");
        startProgressTicker();
    }

    private void reportDownloadProgress(int pageIndex, long bytesRead, long expectedBytes) {
        mainHandler.post(() -> {
            if (!playbackGenerationActive || activePlaybackPageIndex != pageIndex || currentPage != pageIndex) {
                return;
            }
            playbackProgressIsEstimate = true;
            estimatedBytesRead = bytesRead;
            estimatedBytesExpected = expectedBytes;
            int pageUnits = getPageProgressUnits(pageIndex);
            int pageProgress;
            String detail;
            if (expectedBytes > 0) {
                pageProgress = (int) Math.max(0, Math.min(pageUnits - 1, (bytesRead * (long) pageUnits) / Math.max(1L, expectedBytes)));
                detail = String.format(Locale.US, "%d%%", Math.max(0, Math.min(99, (bytesRead * 100L) / Math.max(1L, expectedBytes))));
            } else {
                long elapsed = Math.max(0L, System.currentTimeMillis() - estimatedPlaybackStartedAt);
                pageProgress = (int) Math.max(1, Math.min(pageUnits - 1, elapsed / 300));
                detail = formatBytes(bytesRead);
            }
            int playbackProgress = getPlaybackBarProgressForPageOffsetUnits(pageIndex, pageProgress);
            if (playbackSeek != null && !playbackSeekUserTouch) {
                playbackSeek.setMax(getPlaybackBarMax());
                playbackSeek.setProgress(playbackProgress);
            }
            setPlaybackLabel("Receiving Kokoro audio: " + detail + " (" + getPlaybackScopeName() + " stream estimate; seek available after full audio arrives)");
        });
    }

    private void togglePlayPause() {
        if (player != null && playbackPrepared) {
            try {
                if (player.isPlaying()) {
                    player.pause();
                    updatePlayPauseButton();
                    updatePlaybackProgress();
                    setStatus("Paused page " + (preparedPageIndex + 1) + ".");
                } else {
                    applyPlaybackRate(player);
                    applyPlaybackVolume();
                    player.start();
                    updatePlayPauseButton();
                    setStatus("Playing page " + (preparedPageIndex + 1) + " of " + pages.size() + ".");
                    startProgressTicker();
                }
            } catch (Exception ex) {
                setStatus("Could not toggle playback: " + ex.getMessage());
            }
            return;
        }
        if (playbackGenerationActive) {
            setStatus("Audio is still being generated. Progress is an estimate until the full stream arrives.");
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
        currentPage = target.pageIndex;
        pendingSeekPageIndex = target.pageIndex;
        pendingSeekPageOffsetUnits = target.pageOffsetUnits;
        saveSession();
        updatePageViews();

        if (player != null && playbackPrepared && preparedPageIndex == target.pageIndex) {
            try {
                int duration = player.getDuration();
                int seekMs = msForPageOffsetUnits(target.pageIndex, target.pageOffsetUnits, duration);
                player.seekTo(seekMs);
                clearPendingDocumentSeek();
                updatePlaybackProgress();
                setStatus("Seeked to page " + (target.pageIndex + 1) + " at " + formatDuration(seekMs) + ".");
                return;
            } catch (Exception ex) {
                setStatus("Seek failed: " + ex.getMessage());
                return;
            }
        }

        if (playbackGenerationActive && activePlaybackPageIndex == target.pageIndex) {
            setStatus("Seeking to page " + (target.pageIndex + 1) + " when the Kokoro stream finishes.");
            return;
        }

        if (useWholeTextProgress()) {
            setStatus("Seeking whole text to page " + (target.pageIndex + 1) + ".");
        } else {
            setStatus("Seeking current page " + (target.pageIndex + 1) + ".");
        }
        generateAndPlayCurrentPage();
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
        if (player != null && playbackPrepared && preparedPageIndex == target.pageIndex) {
            try {
                int duration = player.getDuration();
                int seekMs = msForPageOffsetUnits(target.pageIndex, target.pageOffsetUnits, duration);
                String noun = useWholeTextProgress() ? "document" : "page";
                setPlaybackLabel("Seek " + noun + " to " + percent + "%: page " + (target.pageIndex + 1) + ", " + formatDuration(seekMs) + " / " + formatDuration(duration));
                return;
            } catch (Exception ignored) {
            }
        }
        if (useWholeTextProgress()) {
            setPlaybackLabel("Seek document to " + percent + "%: page " + (target.pageIndex + 1) + " (estimate until that page audio is ready)");
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
        if (playbackGenerationActive && !playbackPrepared) {
            int pageIndex = activePlaybackPageIndex >= 0 ? activePlaybackPageIndex : currentPage;
            long elapsed = Math.max(0L, System.currentTimeMillis() - estimatedPlaybackStartedAt);
            int pageUnits = getPageProgressUnits(pageIndex);
            int pageProgress;
            String detail;
            if (estimatedBytesExpected > 0) {
                pageProgress = (int) Math.max(0, Math.min(pageUnits - 1, (estimatedBytesRead * (long) pageUnits) / Math.max(1L, estimatedBytesExpected)));
                detail = String.format(Locale.US, "Receiving Kokoro audio: %d%%", Math.max(0, Math.min(99, (estimatedBytesRead * 100L) / Math.max(1L, estimatedBytesExpected))));
            } else if (estimatedBytesRead > 0) {
                pageProgress = (int) Math.max(1, Math.min(pageUnits - 1, elapsed / 300));
                detail = "Receiving Kokoro audio: " + formatBytes(estimatedBytesRead);
            } else {
                pageProgress = (int) Math.max(0, Math.min(pageUnits - 1, elapsed / 300));
                detail = "Preparing audio...";
            }
            int playbackProgress = getPlaybackBarProgressForPageOffsetUnits(pageIndex, pageProgress);
            if (playbackSeek != null && !playbackSeekUserTouch && playbackSeek.getProgress() < playbackProgress) {
                playbackSeek.setMax(getPlaybackBarMax());
                playbackSeek.setProgress(playbackProgress);
            }
            setPlaybackLabel(detail + " (" + getPlaybackScopeName() + " estimate; " + getTrackBuildStatusLabel() + ")");
            mainHandler.postDelayed(playbackProgressTicker, 500);
            return;
        }
        if (player == null || !playbackPrepared) {
            updatePlayPauseButton();
            return;
        }
        try {
            int duration = player.getDuration();
            int position = player.getCurrentPosition();
            if (duration > 0) {
                int playbackProgress = getPlaybackBarProgressForPagePosition(preparedPageIndex, position, duration);
                if (playbackSeek != null && !playbackSeekUserTouch) {
                    playbackSeek.setMax(getPlaybackBarMax());
                    playbackSeek.setProgress(playbackProgress);
                }
                int percent = Math.round((playbackProgress * 100f) / Math.max(1, getPlaybackBarMax()));
                if (useWholeTextProgress()) {
                    String estimateSuffix = allPagesCached() ? "" : " est";
                    setPlaybackLabel(String.format(Locale.US, "Document%s: %d%% • page %d/%d: %s / %s • %s", estimateSuffix, percent, preparedPageIndex + 1, pages.size(), formatDuration(position), formatDuration(duration), getTrackBuildStatusLabel()));
                } else {
                    setPlaybackLabel(String.format(Locale.US, "Page: %d%% • page %d/%d: %s / %s", percent, preparedPageIndex + 1, pages.size(), formatDuration(position), formatDuration(duration)));
                }
            } else {
                playbackProgressIsEstimate = true;
                int playbackProgress = Math.min(getPlaybackBarEndForPage(preparedPageIndex) - 1, Math.max(getPlaybackBarStartForPage(preparedPageIndex), playbackSeek == null ? 0 : playbackSeek.getProgress() + 5));
                if (playbackSeek != null && !playbackSeekUserTouch) {
                    playbackSeek.setMax(getPlaybackBarMax());
                    playbackSeek.setProgress(playbackProgress);
                }
                setPlaybackLabel((useWholeTextProgress() ? "Document" : "Page") + " progress is estimated; current page duration unavailable yet.");
            }
            updatePlayPauseButton();
            mainHandler.postDelayed(playbackProgressTicker, player.isPlaying() ? 500 : 1000);
        } catch (Exception ex) {
            setPlaybackLabel("Playback: " + ex.getMessage());
        }
    }

    private void resetPlaybackControls(String label) {
        stopProgressTicker();
        playbackPrepared = false;
        playbackProgressIsEstimate = false;
        estimatedBytesRead = 0L;
        estimatedBytesExpected = -1L;
        preparedPageIndex = -1;
        activePlaybackPageIndex = -1;
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
        if (player != null && playbackPrepared) {
            try {
                playPauseButton.setText(player.isPlaying() ? "Pause" : "Play");
                playPauseButton.setEnabled(true);
                return;
            } catch (Exception ignored) {
            }
        }
        playPauseButton.setText("Play");
        playPauseButton.setEnabled(!playbackGenerationActive && !pages.isEmpty());
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
        if (player != null) {
            try {
                float volume = getPlaybackVolume();
                player.setVolume(volume, volume);
            } catch (Exception ignored) {
            }
        }
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

    private int getPlaybackBarEndForPage(int pageIndex) {
        return useWholeTextProgress() ? getPageEndUnit(pageIndex) : PAGE_PROGRESS_MAX;
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

    private int getWholePlaybackProgressForPagePosition(int pageIndex, int positionMs, int durationMs) {
        int start = getPageStartUnit(pageIndex);
        int units = getPageProgressUnits(pageIndex);
        if (durationMs <= 0) {
            return clampWholePlaybackProgress(start);
        }
        int offset = (int) Math.max(0, Math.min(units, (positionMs * (long) units) / Math.max(1, durationMs)));
        return clampWholePlaybackProgress(start + offset);
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

    private void playAudio(File file, int pageIndex) {
        try {
            int savedPendingSeekPageIndex = pendingSeekPageIndex;
            int savedPendingSeekPageOffsetUnits = pendingSeekPageOffsetUnits;
            releasePlayer();
            pendingSeekPageIndex = savedPendingSeekPageIndex;
            pendingSeekPageOffsetUnits = savedPendingSeekPageOffsetUnits;
            player = new MediaPlayer();
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                player.setAudioAttributes(attrs);
            }
            player.setDataSource(file.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                playbackPrepared = true;
                playbackGenerationActive = false;
                playbackProgressIsEstimate = false;
                preparedPageIndex = pageIndex;
                activePlaybackPageIndex = pageIndex;
                applyPlaybackRate(mp);
                applyPlaybackVolume();
                if (pendingSeekPageIndex == pageIndex && pendingSeekPageOffsetUnits >= 0) {
                    try {
                        int targetMs = msForPageOffsetUnits(pageIndex, pendingSeekPageOffsetUnits, mp.getDuration());
                        mp.seekTo(targetMs);
                    } catch (Exception ignored) {
                    }
                    clearPendingDocumentSeek();
                }
                mp.start();
                updatePlayPauseButton();
                updatePlaybackProgress();
                setStatus("Playing page " + (pageIndex + 1) + " of " + pages.size() + " with " + getPlaybackScopeName() + " progress.");
                ensurePrefetchAhead(pageIndex, false);
            });
            player.setOnCompletionListener(mp -> {
                updatePlayPauseButton();
                try {
                    int duration = mp.getDuration();
                    if (playbackSeek != null && duration > 0 && !playbackSeekUserTouch) {
                        playbackSeek.setMax(getPlaybackBarMax());
                        playbackSeek.setProgress(getPlaybackBarEndForPage(pageIndex));
                    }
                    setPlaybackLabel("Finished page " + (pageIndex + 1) + ": " + formatDuration(duration));
                } catch (Exception ignored) {
                    setPlaybackLabel("Finished page " + (pageIndex + 1) + ".");
                }
                if (prefBool("autoNext", true) && currentPage + 1 < pages.size()) {
                    currentPage++;
                    saveSession();
                    updatePageViews();
                    generateAndPlayCurrentPage();
                } else {
                    setStatus("Finished page " + (pageIndex + 1) + ".");
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                resetPlaybackControls("Playback error");
                setStatus("Playback error: what=" + what + " extra=" + extra + ". Try mp3 response format if another format fails.");
                return true;
            });
            player.prepareAsync();
        } catch (Exception ex) {
            setStatus("Could not play audio: " + ex.getMessage());
        }
    }

    private void applyPlaybackRate(MediaPlayer mp) {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            try {
                mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(getPlaybackRate()));
            } catch (Exception ignored) {
            }
        }
    }

    private void releasePlayer() {
        stopProgressTicker();
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        playbackPrepared = false;
        playbackGenerationActive = false;
        playbackProgressIsEstimate = false;
        estimatedBytesRead = 0L;
        estimatedBytesExpected = -1L;
        preparedPageIndex = -1;
        activePlaybackPageIndex = -1;
        resetPlaybackControls("Playback: idle");
    }

    private void updatePageViews() {
        if (textEdit == null || pageLabel == null || pageSeek == null) {
            return;
        }
        if (pages.isEmpty()) {
            pageLabel.setText("No pages yet");
            cacheLabel.setText("Server: " + getServerBase() + "  Voice: " + getVoice() + " • " + getTrackBuildStatusLabel());
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
        cacheLabel.setText(String.format(Locale.US, "%s • %,d chars • %s • %s @ %.2fx • %s", cachedText, text.length(), getVoice(), getResponseFormat(), getTtsSpeed(), getTrackBuildStatusLabel()));
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

    private File audioFileFor(int pageIndex, String text) {
        return new File(getAudioCacheDir(), audioFileNameFor(pageIndex, text));
    }

    private String audioFileNameFor(int pageIndex, String text) {
        String format = getResponseFormat();
        String key = getTtsSettingsKey() + "\n" + pageIndex + "\n" + text;
        String digest = sha256(key).substring(0, 24);
        return String.format(Locale.US, "page_%04d_%s.%s", pageIndex + 1, digest, safeExtension(format));
    }

    private String safeExtension(String format) {
        String f = format.toLowerCase(Locale.US).trim();
        if (f.matches("[a-z0-9]{2,5}")) {
            return f;
        }
        return "mp3";
    }

    private File getAudioCacheDir() {
        return new File(getCacheDir(), "kokoro_audio");
    }

    private int clearAudioCache() {
        releasePlayer();
        File dir = getAudioCacheDir();
        int count = deleteChildren(dir);
        updatePageViews();
        setStatus("Cleared " + count + " transient cached audio file(s). Saved history audio is kept.");
        return count;
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
        prefs.edit()
                .putString("lastText", savedText == null ? "" : savedText)
                .putInt("lastPage", currentPage)
                .apply();
        persistCurrentHistoryMetadata();
    }

    private void restoreLastSession(boolean autoPlay) {
        String text = prefs.getString("lastText", "");
        if (text == null || text.trim().isEmpty()) {
            setStatus("No saved text session yet.");
            return;
        }
        int savedPage = prefs.getInt("lastPage", 0);
        setFullTextAndPaginate(text, savedPage, autoPlay, "Loaded saved session.");
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

    private double getTtsSpeed() {
        try {
            double value = Double.parseDouble(prefString("speed", "1.0").trim());
            return Math.max(0.25, Math.min(4.0, value));
        } catch (Exception ex) {
            return 1.0;
        }
    }

    private float getPlaybackRate() {
        try {
            float value = Float.parseFloat(prefString("playbackRate", "1.0").trim());
            return Math.max(0.25f, Math.min(4.0f, value));
        } catch (Exception ex) {
            return 1.0f;
        }
    }

    private String getResponseFormat() {
        String value = prefString("responseFormat", "mp3").trim().toLowerCase(Locale.US);
        if (value.isEmpty()) {
            return "mp3";
        }
        switch (value) {
            case "mp3":
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

    private int getMaxChars() {
        try {
            int value = Integer.parseInt(prefString("maxChars", "3200").trim());
            return Math.max(500, Math.min(12000, value));
        } catch (Exception ex) {
            return 3200;
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

    private File historyAudioFileFor(String sessionId, int pageIndex, String text) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        return new File(historySessionDir(sessionId), audioFileNameFor(pageIndex, text));
    }

    private String safeFileName(String name) {
        String safe = name == null ? "" : name.replaceAll("[^A-Za-z0-9_.-]", "_");
        return safe.isEmpty() ? "session" : safe;
    }

    private void ensureCurrentHistorySession() {
        if (fullText == null || fullText.trim().isEmpty()) {
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
        JSONObject settings = new JSONObject();
        settings.put("server", getServerBase());
        settings.put("model", getModel());
        settings.put("voice", getVoice());
        settings.put("speed", prefString("speed", "1.0"));
        settings.put("playbackRate", prefString("playbackRate", "1.0"));
        settings.put("responseFormat", getResponseFormat());
        settings.put("stream", prefBool("stream", true));
        settings.put("langCode", getLangCode());
        settings.put("maxChars", prefString("maxChars", "3200"));
        settings.put("normalize", prefBool("normalize", true));
        settings.put("unitNorm", prefBool("unitNorm", false));
        settings.put("urlNorm", prefBool("urlNorm", true));
        settings.put("emailNorm", prefBool("emailNorm", true));
        settings.put("pluralNorm", prefBool("pluralNorm", true));
        settings.put("phoneNorm", prefBool("phoneNorm", true));
        return settings;
    }

    private void applySettingsSnapshot(JSONObject settings) {
        if (settings == null) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        String[] stringKeys = new String[]{"server", "model", "voice", "speed", "playbackRate", "responseFormat", "langCode", "maxChars"};
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
        if (currentSessionId == null || currentSessionId.trim().isEmpty() || fullText == null || fullText.trim().isEmpty()) {
            return;
        }
        try {
            File dir = historySessionDir(currentSessionId);
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (currentSessionCreatedAt <= 0L) {
                currentSessionCreatedAt = now;
            }
            JSONObject meta = new JSONObject();
            meta.put("id", currentSessionId);
            meta.put("createdAt", currentSessionCreatedAt);
            meta.put("updatedAt", now);
            meta.put("currentPage", currentPage);
            meta.put("pageCount", pages.size());
            meta.put("charCount", fullText.length());
            meta.put("title", titleForText(fullText));
            meta.put("text", fullText);
            meta.put("settings", currentSettingsSnapshot());
            meta.put("audioCount", countAvailableAudioFilesForSession(currentSessionId));
            writeString(new File(dir, "session.json"), meta.toString(2));
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

    private void persistGeneratedAudio(String sessionId, int pageIndex, String text, File source) {
        if (sessionId == null || sessionId.trim().isEmpty() || source == null || !source.exists() || source.length() == 0) {
            return;
        }
        try {
            File dst = historyAudioFileFor(sessionId, pageIndex, text);
            if (dst == null) {
                return;
            }
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            if (!dst.equals(source)) {
                copyFile(source, dst);
            }
            persistCurrentHistoryMetadata();
            mainHandler.post(this::updatePageViews);
        } catch (Exception ignored) {
        }
    }

    private File findPlayableAudioFile(int pageIndex, String text) {
        return findPlayableAudioFile(currentSessionId, pageIndex, text);
    }

    private File findPlayableAudioFile(String sessionId, int pageIndex, String text) {
        File cached = audioFileFor(pageIndex, text);
        if (cached.exists() && cached.length() > 0) {
            return cached;
        }
        File historical = historyAudioFileFor(sessionId, pageIndex, text);
        if (historical != null && historical.exists() && historical.length() > 0) {
            return historical;
        }
        return null;
    }

    private boolean audioAvailableForPage(int pageIndex, String text) {
        File f = findPlayableAudioFile(pageIndex, text);
        return f != null && f.exists() && f.length() > 0;
    }

    private int countAvailableAudioFilesForSession(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty() || pages.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < pages.size(); i++) {
            if (findPlayableAudioFile(sessionId, i, pages.get(i)) != null) {
                count++;
            }
        }
        return count;
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
        boolean fetching = getActiveGenerationCount() > 0 || playbackGenerationActive;
        if (have >= pages.size()) {
            return "Track: complete/offline";
        }
        return String.format(Locale.US, "Track: %s %d/%d", fetching ? "fetching" : "built", have, pages.size());
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

    private File waitForGeneratedAudio(String sessionId, int pageIndex, String text, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            File existing = findPlayableAudioFile(sessionId, pageIndex, text);
            if (existing != null && existing.exists() && existing.length() > 0) {
                return existing;
            }
            Thread.sleep(350L);
        }
        return null;
    }

    private void ensurePrefetchAhead(int fromPageIndex, boolean announce) {
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
        pregenerateNextPages(count, announce);
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
        ArrayList<JSONObject> sessions = readHistorySessions();
        for (int i = limit; i < sessions.size(); i++) {
            String id = sessions.get(i).optString("id", "");
            if (!id.isEmpty() && !id.equals(currentSessionId)) {
                deleteChildren(historySessionDir(id));
                //noinspection ResultOfMethodCallIgnored
                historySessionDir(id).delete();
            }
        }
    }

    private void showHistoryDialog() {
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
        File dir = historySessionDir(sessionId);
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (File f : files) {
            String name = f.getName().toLowerCase(Locale.US);
            if (f.isFile() && (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".aac") || name.endsWith(".flac") || name.endsWith(".opus") || name.endsWith(".pcm"))) {
                count++;
            }
        }
        return count;
    }

    private JSONObject readJsonFile(File file) {
        try {
            if (file == null || !file.exists()) {
                return null;
            }
            try (InputStream is = new FileInputStream(file)) {
                return new JSONObject(readAll(is));
            }
        } catch (Exception ex) {
            return null;
        }
    }

    private void writeString(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void copyFile(File source, File dest) throws Exception {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (InputStream is = new FileInputStream(source); FileOutputStream os = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = is.read(buffer)) != -1) {
                os.write(buffer, 0, n);
            }
        }
    }

    private String formatDateShort(long timeMs) {
        if (timeMs <= 0L) {
            return "unknown";
        }
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(timeMs));
    }

    private void showSettingsDialog() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        rootLayout = root;
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
        addLabel(formatCol, "Format");
        EditText formatEdit = addDialogEdit(formatCol, getResponseFormat(), true, 1);
        LinearLayout langCol = col(row3, 1);
        addLabel(langCol, "Language code, blank = auto");
        EditText langCodeEdit = addDialogEdit(langCol, getLangCode(), true, 1);

        LinearLayout row4 = row(root);
        LinearLayout charsCol = col(row4, 1);
        addLabel(charsCol, "Chars per page/chunk");
        EditText maxCharsEdit = addDialogEdit(charsCol, prefString("maxChars", "3200"), true, 1);
        maxCharsEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout historyCol = col(row4, 1);
        addLabel(historyCol, "History sessions to keep");
        EditText historyLimitEdit = addDialogEdit(historyCol, prefString("historyLimit", "20"), true, 1);
        historyLimitEdit.setInputType(InputType.TYPE_CLASS_NUMBER);

        CheckBox darkModeBox = addDialogCheck(root, "Dark mode", prefBool("darkMode", true));
        CheckBox streamBox = addDialogCheck(root, "Ask server to stream response", prefBool("stream", true));
        CheckBox autoNextBox = addDialogCheck(root, "Auto-generate/play next page", prefBool("autoNext", true));
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
        Button pregenerateButton = addDialogButton(sessionButtons, "Pre-gen Next 3", 1);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(scroll)
                .setPositiveButton("Save", null)
                .setNegativeButton("Close", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                prefs.edit()
                        .putString("server", normalizeServer(serverEdit.getText().toString()))
                        .putString("model", modelEdit.getText().toString().trim())
                        .putString("voice", voiceEdit.getText().toString().trim())
                        .putString("speed", speedEdit.getText().toString().trim())
                        .putString("playbackRate", playbackRateEdit.getText().toString().trim())
                        .putString("responseFormat", formatEdit.getText().toString().trim().toLowerCase(Locale.US))
                        .putString("langCode", langCodeEdit.getText().toString().trim())
                        .putString("maxChars", maxCharsEdit.getText().toString().trim())
                        .putString("historyLimit", historyLimitEdit.getText().toString().trim())
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
                if (!fullText.trim().isEmpty()) {
                    int keepPage = currentPage;
                    pages.clear();
                    pages.addAll(splitIntoPages(fullText, getMaxChars()));
                    rebuildPlaybackProgressIndex();
                    currentPage = Math.max(0, Math.min(keepPage, Math.max(0, pages.size() - 1)));
                }
                updatePageViews();
                applyThemeToTree(rootLayout);
                pruneHistoryToLimit();
                ensurePrefetchAhead(currentPage, false);
                if (playbackSeek != null && !playbackSeekUserTouch) {
                    playbackSeek.setMax(getPlaybackBarMax());
                    if (player == null || !playbackPrepared) {
                        playbackSeek.setProgress(getPlaybackBarStartForPage(currentPage));
                    }
                }
                updatePlaybackProgress();
                saveSession();
                setStatus("Settings saved.");
                dialog.dismiss();
            });
        });

        healthButton.setOnClickListener(v -> healthCheckServer(serverEdit));
        fetchVoicesButton.setOnClickListener(v -> fetchVoicesIntoField(serverEdit, voiceEdit));
        clearCacheButton.setOnClickListener(v -> clearAudioCache());
        loadButton.setOnClickListener(v -> restoreLastSession(false));
        historyDialogButton.setOnClickListener(v -> showHistoryDialog());
        pregenerateButton.setOnClickListener(v -> pregenerateNextPages(3, true));

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
                    URL url = new URL(base + path);
                    conn = (HttpURLConnection) url.openConnection();
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
                        mainHandler.post(() -> {
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
                        conn.disconnect();
                    }
                }
            }
            final String message = "Health check failed: " + lastError;
            mainHandler.post(() -> {
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
                URL url = new URL(base + "/v1/audio/voices");
                conn = (HttpURLConnection) url.openConnection();
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
                mainHandler.post(() -> showVoiceDialogForField(voices, voiceEdit));
            } catch (Exception ex) {
                mainHandler.post(() -> setStatus("Could not fetch voices: " + ex.getMessage()));
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
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
        row.setPadding(0, dp(4), 0, dp(4));
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        params.setMargins(dp(2), 0, dp(2), 0);
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
