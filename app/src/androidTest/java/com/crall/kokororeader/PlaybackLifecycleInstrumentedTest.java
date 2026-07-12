package com.crall.kokororeader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class PlaybackLifecycleInstrumentedTest {
    private Context context;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PlaybackService.stop(context);
        PlaybackDocumentStore.clear(context);
        AtomicFileStore.deleteTree(KokoroAudioRepository.cacheDirectory(context));
        AtomicFileStore.deleteTree(new java.io.File(context.getFilesDir(), "kokoro_history"));
        CurrentTextStore.clear(context);
    }

    @After
    public void tearDown() {
        PlaybackService.stop(context);
        waitUntil(() -> !PlaybackService.getSnapshot().isActive(), 5000L, "service did not stop");
        PlaybackDocumentStore.clear(context);
        AtomicFileStore.deleteTree(KokoroAudioRepository.cacheDirectory(context));
        AtomicFileStore.deleteTree(new java.io.File(context.getFilesDir(), "kokoro_history"));
        CurrentTextStore.clear(context);
    }

    @Test
    public void rotationDoesNotInterruptPlaybackAndQueueAdvances() throws Exception {
        try (LocalWavServer server = new LocalWavServer()) {
            SharedPreferences prefs = context.getSharedPreferences("kokoro_reader_prefs", Context.MODE_PRIVATE);
            prefs.edit()
                    .putBoolean("autoNext", true)
                    .putString("autoNextDelayMs", "0")
                    .putString("prefetchPages", "0")
                    .putString("historyLimit", "0")
                    .putString("playbackRate", "1.0")
                    .apply();

            ArrayList<String> pages = new ArrayList<>(Arrays.asList(
                    "first page used by the connected playback test",
                    "second page proves auto advance survived recreation"));
            TtsConfig config = new TtsConfig(
                    server.baseUrl(),
                    "kokoro",
                    "af_bella",
                    1.0,
                    "wav",
                    false,
                    "",
                    true,
                    false,
                    true,
                    true,
                    true,
                    true);
            PlaybackDocumentStore.Document document = PlaybackDocumentStore.write(
                    context, pages, "instrumented-session", 0, config);
            CurrentTextStore.write(context, String.join("\n\n", pages));

            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                PlaybackService.playDocument(context, 0, -1);
                waitUntil(
                        () -> PlaybackService.getSnapshot().playing
                                && PlaybackService.getSnapshot().pageIndex == 0,
                        15000L,
                        "first page never began playback");

                scenario.recreate();

                PlaybackService.Snapshot afterRecreation = PlaybackService.getSnapshot();
                assertTrue(afterRecreation.isActive());
                assertEquals(document.id, afterRecreation.documentId);

                waitUntil(
                        () -> PlaybackService.getSnapshot().pageIndex == 1
                                && PlaybackService.getSnapshot().playing
                                && server.requestCount.get() >= 2,
                        15000L,
                        "queue did not auto-advance and play the second page");
                assertTrue("expected two Kokoro requests", server.requestCount.get() >= 2);
            }
        }
    }

    private static void waitUntil(Check check, long timeoutMs, String failureMessage) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (check.evaluate()) {
                return;
            }
            SystemClock.sleep(50L);
        }
        throw new AssertionError(failureMessage + ": " + PlaybackService.getSnapshot().status);
    }

    private interface Check {
        boolean evaluate();
    }

    private static final class LocalWavServer implements AutoCloseable {
        final AtomicInteger requestCount = new AtomicInteger();
        private final ServerSocket serverSocket;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private volatile boolean closed;

        LocalWavServer() throws Exception {
            serverSocket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            executor.submit(this::acceptLoop);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + serverSocket.getLocalPort();
        }

        private void acceptLoop() {
            while (!closed) {
                try {
                    Socket socket = serverSocket.accept();
                    executor.submit(() -> handle(socket));
                } catch (Exception ex) {
                    if (!closed) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }

        private void handle(Socket socket) {
            try (Socket client = socket;
                 InputStream rawInput = new BufferedInputStream(client.getInputStream());
                 OutputStream output = new BufferedOutputStream(client.getOutputStream())) {
                int contentLength = readHeaders(rawInput);
                for (int remaining = contentLength; remaining > 0; ) {
                    int value = rawInput.read();
                    if (value < 0) {
                        break;
                    }
                    remaining--;
                }

                int request = requestCount.incrementAndGet();
                byte[] wav = silentWav(request == 1 ? 1500 : 5000);
                String headers = String.format(
                        Locale.US,
                        "HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: %d\r\nConnection: close\r\n\r\n",
                        wav.length);
                output.write(headers.getBytes(StandardCharsets.US_ASCII));
                output.write(wav);
                output.flush();
            } catch (Exception ignored) {
            }
        }

        private static int readHeaders(InputStream input) throws Exception {
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int matched = 0;
            int value;
            while ((value = input.read()) != -1) {
                header.write(value);
                if ((matched == 0 || matched == 2) && value == '\r') {
                    matched++;
                } else if ((matched == 1 || matched == 3) && value == '\n') {
                    matched++;
                    if (matched == 4) {
                        break;
                    }
                } else {
                    matched = 0;
                }
            }
            String text = header.toString(StandardCharsets.US_ASCII.name());
            for (String line : text.split("\\r\\n")) {
                if (line.toLowerCase(Locale.US).startsWith("content-length:")) {
                    return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            return 0;
        }

        private static byte[] silentWav(int durationMs) throws Exception {
            int sampleRate = 8000;
            int channels = 1;
            int bitsPerSample = 16;
            int sampleCount = sampleRate * durationMs / 1000;
            int dataSize = sampleCount * channels * bitsPerSample / 8;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(44 + dataSize);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeAscii(output, "RIFF");
                writeLittleEndianInt(output, 36 + dataSize);
                writeAscii(output, "WAVE");
                writeAscii(output, "fmt ");
                writeLittleEndianInt(output, 16);
                writeLittleEndianShort(output, 1);
                writeLittleEndianShort(output, channels);
                writeLittleEndianInt(output, sampleRate);
                writeLittleEndianInt(output, sampleRate * channels * bitsPerSample / 8);
                writeLittleEndianShort(output, channels * bitsPerSample / 8);
                writeLittleEndianShort(output, bitsPerSample);
                writeAscii(output, "data");
                writeLittleEndianInt(output, dataSize);
                output.write(new byte[dataSize]);
            }
            return bytes.toByteArray();
        }

        private static void writeAscii(DataOutputStream output, String value) throws Exception {
            output.write(value.getBytes(StandardCharsets.US_ASCII));
        }

        private static void writeLittleEndianInt(DataOutputStream output, int value) throws Exception {
            output.writeByte(value & 0xff);
            output.writeByte((value >>> 8) & 0xff);
            output.writeByte((value >>> 16) & 0xff);
            output.writeByte((value >>> 24) & 0xff);
        }

        private static void writeLittleEndianShort(DataOutputStream output, int value) throws Exception {
            output.writeByte(value & 0xff);
            output.writeByte((value >>> 8) & 0xff);
        }

        @Override
        public void close() throws Exception {
            closed = true;
            serverSocket.close();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
