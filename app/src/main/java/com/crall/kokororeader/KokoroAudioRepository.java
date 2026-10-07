package com.crall.kokororeader;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Single authority for Kokoro requests, audio cache identity, and saved audio lookup. */
final class KokoroAudioRepository {
    interface ProgressListener {
        void onProgress(long received, long expected);
    }

    static final class Cancellation {
        private final Set<HttpURLConnection> connections = Collections.newSetFromMap(new ConcurrentHashMap<>());
        private volatile boolean cancelled;

        void cancel() {
            cancelled = true;
            for (HttpURLConnection connection : connections) {
                connection.disconnect();
            }
            connections.clear();
        }

        boolean isCancelled() {
            return cancelled || Thread.currentThread().isInterrupted();
        }

        void throwIfCancelled() throws InterruptedException {
            if (isCancelled()) {
                throw new InterruptedException("Speech generation was cancelled.");
            }
        }

        void register(HttpURLConnection connection) throws InterruptedException {
            throwIfCancelled();
            connections.add(connection);
            if (isCancelled()) {
                connection.disconnect();
                connections.remove(connection);
                throw new InterruptedException("Speech generation was cancelled.");
            }
        }

        void unregister(HttpURLConnection connection) {
            connections.remove(connection);
        }
    }

    private static final class InFlightGeneration {
        final CountDownLatch finished = new CountDownLatch(1);
        volatile File result;
    }

    private static final ConcurrentHashMap<String, InFlightGeneration> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final long MIN_AUDIO_BYTES = 16L;

    private KokoroAudioRepository() {
    }

    static File cacheDirectory(Context context) {
        return new File(context.getCacheDir(), "kokoro_audio");
    }

    static File cacheFile(Context context, TtsConfig config, int pageIndex, String text) {
        return new File(cacheDirectory(context), audioFileName(config, pageIndex, text));
    }

    static String audioFileName(TtsConfig config, int pageIndex, String text) {
        String key = config.settingsKey() + "\n" + pageIndex + "\n" + text;
        return String.format(
                Locale.US,
                "page_%04d_%s.%s",
                pageIndex + 1,
                sha256(key).substring(0, 24),
                config.responseFormat);
    }

    static File historyAudioFile(Context context, String sessionId, TtsConfig config, int pageIndex, String text) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        File session = new File(new File(context.getFilesDir(), "kokoro_history"), safeFileName(sessionId));
        return new File(new File(session, "audio"), audioFileName(config, pageIndex, text));
    }

    static File findPlayable(
            Context context,
            TtsConfig config,
            int historyLimit,
            String sessionId,
            int pageIndex,
            String text) {
        File cached = cacheFile(context, config, pageIndex, text);
        if (isUsableAudio(cached)) {
            return cached;
        }
        if (historyLimit <= 0 || sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        File historical = historyAudioFile(context, sessionId, config, pageIndex, text);
        if (isUsableAudio(historical)) {
            return historical;
        }
        File session = new File(new File(context.getFilesDir(), "kokoro_history"), safeFileName(sessionId));
        File legacy = new File(session, audioFileName(config, pageIndex, text));
        return isUsableAudio(legacy) ? legacy : null;
    }

    static File requestSpeech(
            Context context,
            TtsConfig config,
            int historyLimit,
            String sessionId,
            int pageIndex,
            String text,
            Cancellation cancellation,
            ProgressListener progressListener) throws Exception {
        Cancellation scope = cancellation == null ? new Cancellation() : cancellation;
        File cached = cacheFile(context, config, pageIndex, text);
        String generationKey = cached.getAbsolutePath();

        while (true) {
            scope.throwIfCancelled();
            File existing = findPlayable(context, config, historyLimit, sessionId, pageIndex, text);
            if (existing != null) {
                return existing;
            }

            InFlightGeneration mine = new InFlightGeneration();
            InFlightGeneration prior = IN_FLIGHT.putIfAbsent(generationKey, mine);
            if (prior != null) {
                while (!prior.finished.await(250L, TimeUnit.MILLISECONDS)) {
                    scope.throwIfCancelled();
                }
                scope.throwIfCancelled();
                if (prior.result != null && isUsableAudio(prior.result)) {
                    return prior.result;
                }
                // The owner failed or was cancelled. Retry once this caller can become owner.
                continue;
            }

            try {
                generate(context, config, historyLimit, sessionId, pageIndex, text, cached, scope, progressListener);
                mine.result = cached;
                return cached;
            } finally {
                mine.finished.countDown();
                IN_FLIGHT.remove(generationKey, mine);
            }
        }
    }

    private static void generate(
            Context context,
            TtsConfig config,
            int historyLimit,
            String sessionId,
            int pageIndex,
            String text,
            File cached,
            Cancellation cancellation,
            ProgressListener progressListener) throws Exception {
        File parent = cached.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create audio cache directory.");
        }

        HttpURLConnection connection = (HttpURLConnection) URI.create(
                config.serverBase + "/v1/audio/speech").toURL().openConnection();
        File temporary = null;
        try {
            cancellation.register(connection);
            temporary = File.createTempFile("kokoro_", ".download", parent);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(180000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", acceptHeaderForFormat(config.responseFormat));

            byte[] body = config.requestPayload(text).toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            cancellation.throwIfCancelled();
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            cancellation.throwIfCancelled();
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException(
                        "HTTP " + code + " from TTS endpoint (" + config.endpointSummary() + "): "
                                + readError(connection));
            }

            long expected = connection.getContentLengthLong();
            long total = 0L;
            long lastReportedBytes = 0L;
            long lastReportedAt = 0L;
            if (progressListener != null) {
                progressListener.onProgress(0L, expected);
            }
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    cancellation.throwIfCancelled();
                    output.write(buffer, 0, count);
                    total += count;
                    long now = System.currentTimeMillis();
                    if (progressListener != null
                            && (total - lastReportedBytes >= 65536L || now - lastReportedAt >= 750L)) {
                        lastReportedBytes = total;
                        lastReportedAt = now;
                        progressListener.onProgress(total, expected);
                    }
                }
                output.flush();
                output.getFD().sync();
            }
            cancellation.throwIfCancelled();
            if (expected >= 0L && total != expected) {
                throw new IllegalStateException(
                        "TTS response ended early (" + config.endpointSummary() + "): received "
                                + total + " of " + expected + " bytes.");
            }
            if (!isUsableAudio(temporary)) {
                throw new IllegalStateException(
                        "TTS endpoint returned an empty or truncated audio file ("
                                + config.endpointSummary() + ").");
            }
            synchronized (AtomicFileStore.class) {
                cancellation.throwIfCancelled();
                AtomicFileStore.copy(temporary, cached);
            }
            if (progressListener != null) {
                progressListener.onProgress(total, total);
            }
            try {
                persistGeneratedAudio(
                        context, historyLimit, sessionId, config, pageIndex, text, cached, cancellation);
            } catch (Exception ignored) {
                // A history-copy failure must not discard otherwise valid playable cache audio.
            }
        } finally {
            cancellation.unregister(connection);
            connection.disconnect();
            //noinspection ResultOfMethodCallIgnored
            if (temporary != null) {
                temporary.delete();
            }
        }
    }

    private static void persistGeneratedAudio(
            Context context,
            int historyLimit,
            String sessionId,
            TtsConfig config,
            int pageIndex,
            String text,
            File source,
            Cancellation cancellation) throws Exception {
        if (historyLimit <= 0 || sessionId == null || sessionId.trim().isEmpty()) {
            return;
        }
        File destination = historyAudioFile(context, sessionId, config, pageIndex, text);
        if (destination != null && !destination.equals(source)) {
            synchronized (AtomicFileStore.class) {
                cancellation.throwIfCancelled();
                AtomicFileStore.copy(source, destination);
            }
        }
    }

    private static boolean isUsableAudio(File file) {
        return file != null && file.isFile() && file.length() >= MIN_AUDIO_BYTES;
    }

    private static String acceptHeaderForFormat(String format) {
        switch (format) {
            case "wav":
                return "audio/wav, audio/*, application/octet-stream";
            case "aac":
                return "audio/aac, audio/*, application/octet-stream";
            case "flac":
                return "audio/flac, audio/*, application/octet-stream";
            case "opus":
                return "audio/opus, audio/*, application/octet-stream";
            default:
                return "audio/mpeg, audio/*, application/octet-stream";
        }
    }

    private static String readError(HttpURLConnection connection) {
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

    private static String safeFileName(String value) {
        String safe = value == null ? "" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return safe.isEmpty() ? "session" : safe;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) {
                result.append(String.format(Locale.US, "%02x", item));
            }
            return result.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode()) + "00000000000000000000000000000000";
        }
    }
}
