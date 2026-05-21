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
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "kokoro_reader_prefs";
    private static final String DEFAULT_SERVER_BASE = "http://10.0.2.2:8880";

    private TextView statusView;
    private TextView pageLabel;
    private TextView cacheLabel;
    private PasteAwareEditText textEdit;
    private SeekBar pageSeek;
    private Button prevButton;
    private Button nextButton;
    private Button playClipboardButton;
    private Button playTextButton;
    private Button playPauseButton;
    private TextView playbackLabel;
    private TextView volumeLabel;
    private SeekBar playbackSeek;
    private SeekBar volumeSeek;

    private final ArrayList<String> pages = new ArrayList<>();
    private String fullText = "";
    private int currentPage = 0;
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

        Button settingsButton = new Button(this);
        settingsButton.setText("Settings");
        settingsButton.setAllCaps(false);
        settingsButton.setTextSize(16);
        settingsButton.setMinHeight(dp(52));
        hudRow.addView(settingsButton, new LinearLayout.LayoutParams(dp(116), ViewGroup.LayoutParams.WRAP_CONTENT));

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
        playbackSeek.setMax(1000);
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
        textEdit.setHint("Paste long text here. The app will paginate pasted text so you can flip pages with the arrows, then tap Play text.");
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
        playTextButton.setText("Play text");
        playTextButton.setAllCaps(false);
        playTextButton.setTextSize(22);
        playTextButton.setMinHeight(dp(96));
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.setMargins(dp(6), 0, 0, 0);
        buttonRow.addView(playTextButton, textParams);

        settingsButton.setOnClickListener(v -> showSettingsDialog());
        prevButton.setOnClickListener(v -> movePage(-1));
        nextButton.setOnClickListener(v -> movePage(1));
        playClipboardButton.setOnClickListener(v -> readClipboardSplitGeneratePlay());
        playTextButton.setOnClickListener(v -> playTextFromCurrentPage());
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
                    setStatus("Selected page " + (currentPage + 1) + ". Tap Play text to start here.");
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

    private void playTextFromCurrentPage() {
        if (!ensurePagesFromTextBox()) {
            return;
        }
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
        setStatus(prefix + " " + pages.size() + " page(s)." + (autoPlay ? " Generating page " + (currentPage + 1) + "." : " Use arrows to choose a page, then Play text."));
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
        setStatus("Selected page " + (currentPage + 1) + ". Tap Play text to start here.");
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
        if (cached.exists() && cached.length() > 0) {
            setStatus("Using cached audio for page " + (pageIndex + 1) + ".");
            playAudio(cached, pageIndex);
            updatePageViews();
            pregenerateNextPages(3, false);
            return;
        }
        if (playbackGenerationActive && activePlaybackPageIndex == pageIndex) {
            setStatus("Already generating page " + (pageIndex + 1) + ".");
            return;
        }

        setEstimatedPlaybackProgress(pageIndex, "Preparing page " + (pageIndex + 1) + "... waiting for Kokoro stream");
        setStatus("Generating page " + (pageIndex + 1) + " of " + pages.size() + "...");
        executor.submit(() -> {
            try {
                File out = requestSpeech(text, pageIndex);
                mainHandler.post(() -> {
                    if (activePlaybackPageIndex == pageIndex) {
                        playbackGenerationActive = false;
                    }
                    updatePageViews();
                    if (currentPage == pageIndex) {
                        setStatus("Generated page " + (pageIndex + 1) + ". Playing.");
                        playAudio(out, pageIndex);
                        pregenerateNextPages(3, false);
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
                    File f = audioFileFor(i, pages.get(i));
                    if (!f.exists() || f.length() == 0) {
                        requestSpeech(pages.get(i), i);
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

    private File requestSpeech(String text, int pageIndex) throws Exception {
        File cached = audioFileFor(pageIndex, text);
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
        return cached;
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
            playbackSeek.setMax(1000);
            playbackSeek.setProgress(0);
        }
        setPlaybackLabel(message + " (estimate)");
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
            int progress;
            String detail;
            if (expectedBytes > 0) {
                progress = (int) Math.max(0, Math.min(980, (bytesRead * 1000L) / expectedBytes));
                detail = String.format(Locale.US, "%d%%", progress / 10);
            } else {
                long elapsed = Math.max(0L, System.currentTimeMillis() - estimatedPlaybackStartedAt);
                progress = (int) Math.max(25, Math.min(950, elapsed / 300));
                detail = formatBytes(bytesRead);
            }
            if (playbackSeek != null && !playbackSeekUserTouch) {
                playbackSeek.setMax(1000);
                playbackSeek.setProgress(progress);
            }
            setPlaybackLabel("Receiving Kokoro audio: " + detail + " (stream estimate; seek available after full audio arrives)");
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
        playTextFromCurrentPage();
    }

    private void seekPlaybackTo(int progress) {
        if (player == null || !playbackPrepared) {
            setStatus("Audio is not ready to seek yet.");
            return;
        }
        try {
            int duration = player.getDuration();
            int target = progress;
            if (duration > 0 && playbackSeek != null && playbackSeek.getMax() != duration) {
                target = (int) ((progress / (float) Math.max(1, playbackSeek.getMax())) * duration);
            }
            target = Math.max(0, Math.min(target, Math.max(0, duration - 250)));
            player.seekTo(target);
            updatePlaybackProgress();
            setStatus("Seeked to " + formatDuration(target) + ".");
        } catch (Exception ex) {
            setStatus("Seek failed: " + ex.getMessage());
        }
    }

    private void updatePlaybackLabelForScrub(int progress) {
        if (playbackLabel == null || playbackSeek == null) {
            return;
        }
        if (player != null && playbackPrepared) {
            try {
                int duration = player.getDuration();
                int max = Math.max(1, playbackSeek.getMax());
                int pos = playbackSeek.getMax() == duration ? progress : (int) ((progress / (float) max) * duration);
                setPlaybackLabel("Seek to " + formatDuration(pos) + " / " + formatDuration(duration));
                return;
            } catch (Exception ignored) {
            }
        }
        setPlaybackLabel("Playback position " + (progress / 10) + "% (estimate)");
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
            long elapsed = Math.max(0L, System.currentTimeMillis() - estimatedPlaybackStartedAt);
            int progress;
            String detail;
            if (estimatedBytesExpected > 0) {
                progress = (int) Math.max(0, Math.min(980, (estimatedBytesRead * 1000L) / estimatedBytesExpected));
                detail = String.format(Locale.US, "Receiving Kokoro audio: %d%%", progress / 10);
            } else if (estimatedBytesRead > 0) {
                progress = (int) Math.max(25, Math.min(950, elapsed / 300));
                detail = "Receiving Kokoro audio: " + formatBytes(estimatedBytesRead);
            } else {
                progress = (int) Math.max(0, Math.min(950, elapsed / 300));
                detail = "Preparing audio... " + (progress / 10) + "%";
            }
            if (playbackSeek != null && !playbackSeekUserTouch && playbackSeek.getProgress() < progress) {
                playbackSeek.setMax(1000);
                playbackSeek.setProgress(progress);
            }
            setPlaybackLabel(detail + " (estimate; waiting for full Kokoro stream)");
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
                if (playbackSeek != null && !playbackSeekUserTouch) {
                    playbackSeek.setMax(duration);
                    playbackSeek.setProgress(Math.max(0, Math.min(position, duration)));
                }
                setPlaybackLabel(String.format(Locale.US, "Playback: %s / %s", formatDuration(position), formatDuration(duration)));
            } else {
                playbackProgressIsEstimate = true;
                if (playbackSeek != null && !playbackSeekUserTouch) {
                    playbackSeek.setMax(1000);
                    playbackSeek.setProgress(Math.min(950, playbackSeek.getProgress() + 5));
                }
                setPlaybackLabel("Playback position is estimated; duration unavailable yet.");
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
        if (playbackSeek != null && !playbackSeekUserTouch) {
            playbackSeek.setMax(1000);
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
            releasePlayer();
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
                mp.start();
                updatePlayPauseButton();
                updatePlaybackProgress();
                setStatus("Playing page " + (pageIndex + 1) + " of " + pages.size() + ".");
            });
            player.setOnCompletionListener(mp -> {
                updatePlayPauseButton();
                try {
                    int duration = mp.getDuration();
                    if (playbackSeek != null && duration > 0 && !playbackSeekUserTouch) {
                        playbackSeek.setMax(duration);
                        playbackSeek.setProgress(duration);
                    }
                    setPlaybackLabel("Finished: " + formatDuration(duration));
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
            cacheLabel.setText("Server: " + getServerBase() + "  Voice: " + getVoice());
            pageSeek.setMax(0);
            pageSeek.setProgress(0);
            prevButton.setEnabled(false);
            nextButton.setEnabled(false);
            updatePlayPauseButton();
            return;
        }
        currentPage = Math.max(0, Math.min(currentPage, pages.size() - 1));
        String text = pages.get(currentPage);
        File cached = audioFileFor(currentPage, text);
        String cachedText = (cached.exists() && cached.length() > 0) ? "cached" : "not cached";
        pageLabel.setText(String.format(Locale.US, "Page %d / %d", currentPage + 1, pages.size()));
        cacheLabel.setText(String.format(Locale.US, "%s • %,d chars • %s • %s @ %.2fx", cachedText, text.length(), getVoice(), getResponseFormat(), getTtsSpeed()));
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
        String format = getResponseFormat();
        String key = getTtsSettingsKey() + "\n" + pageIndex + "\n" + text;
        String digest = sha256(key).substring(0, 24);
        String name = String.format(Locale.US, "page_%04d_%s.%s", pageIndex + 1, digest, safeExtension(format));
        return new File(getAudioCacheDir(), name);
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
        setStatus("Cleared " + count + " cached audio file(s).");
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
        addLabel(formatCol, "Format");
        EditText formatEdit = addDialogEdit(formatCol, getResponseFormat(), true, 1);
        LinearLayout langCol = col(row3, 1);
        addLabel(langCol, "Language code, blank = auto");
        EditText langCodeEdit = addDialogEdit(langCol, getLangCode(), true, 1);

        addLabel(root, "Chars per page/chunk");
        EditText maxCharsEdit = addDialogEdit(root, prefString("maxChars", "3200"), true, 1);
        maxCharsEdit.setInputType(InputType.TYPE_CLASS_NUMBER);

        CheckBox streamBox = addDialogCheck(root, "Ask server to stream response", prefBool("stream", true));
        CheckBox autoNextBox = addDialogCheck(root, "Auto-generate/play next page", prefBool("autoNext", true));
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
                        .putBoolean("stream", streamBox.isChecked())
                        .putBoolean("autoNext", autoNextBox.isChecked())
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
                    currentPage = Math.max(0, Math.min(keepPage, Math.max(0, pages.size() - 1)));
                }
                updatePageViews();
                saveSession();
                setStatus("Settings saved.");
                dialog.dismiss();
            });
        });

        healthButton.setOnClickListener(v -> healthCheckServer(serverEdit));
        fetchVoicesButton.setOnClickListener(v -> fetchVoicesIntoField(serverEdit, voiceEdit));
        clearCacheButton.setOnClickListener(v -> clearAudioCache());
        loadButton.setOnClickListener(v -> restoreLastSession(false));
        pregenerateButton.setOnClickListener(v -> pregenerateNextPages(3, true));

        dialog.show();
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
