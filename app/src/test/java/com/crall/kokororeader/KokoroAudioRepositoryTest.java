package com.crall.kokororeader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class KokoroAudioRepositoryTest {
    private Context context;
    private ExecutorService workers;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        workers = Executors.newFixedThreadPool(3);
        AtomicFileStore.deleteTree(KokoroAudioRepository.cacheDirectory(context));
        AtomicFileStore.deleteTree(new File(context.getFilesDir(), "kokoro_history"));
    }

    @After
    public void tearDown() throws Exception {
        workers.shutdownNow();
        assertTrue("worker threads did not stop", workers.awaitTermination(5, TimeUnit.SECONDS));
        AtomicFileStore.deleteTree(KokoroAudioRepository.cacheDirectory(context));
        AtomicFileStore.deleteTree(new File(context.getFilesDir(), "kokoro_history"));
    }

    @Test
    public void successfulGenerationAtomicallyPublishesCacheAndHistory() throws Exception {
        byte[] audio = audioBytes(4096);
        try (FakeKokoroServer server = new FakeKokoroServer(FakeKokoroServer.Mode.SUCCESS, audio)) {
            TtsConfig config = TestFixtures.config(server.baseUrl());
            File generated = KokoroAudioRepository.requestSpeech(
                    context,
                    config,
                    20,
                    "session-1",
                    0,
                    "hello from the test",
                    new KokoroAudioRepository.Cancellation(),
                    null);

            assertTrue(generated.isFile());
            assertArrayEquals(audio, Files.readAllBytes(generated.toPath()));
            File historical = KokoroAudioRepository.historyAudioFile(
                    context, "session-1", config, 0, "hello from the test");
            assertNotNull(historical);
            assertArrayEquals(audio, Files.readAllBytes(historical.toPath()));
            JSONObject request = new JSONObject(server.lastRequestBody);
            assertEquals("hello from the test", request.getString("input"));
            assertEquals("af_bella", request.getString("voice"));
            assertEquals(1, server.requestCount.get());

            File reused = KokoroAudioRepository.requestSpeech(
                    context,
                    config,
                    20,
                    "session-1",
                    0,
                    "hello from the test",
                    new KokoroAudioRepository.Cancellation(),
                    null);
            assertEquals(generated.getCanonicalFile(), reused.getCanonicalFile());
            assertEquals("cache hit unexpectedly contacted Kokoro", 1, server.requestCount.get());
        }
    }

    @Test
    public void concurrentCallersCoalesceToOneHttpRequest() throws Exception {
        byte[] audio = audioBytes(8192);
        try (FakeKokoroServer server = new FakeKokoroServer(
                FakeKokoroServer.Mode.BLOCK_BEFORE_RESPONSE, audio)) {
            TtsConfig config = TestFixtures.config(server.baseUrl());
            Future<File> first = workers.submit(() -> request(config, "deduplicated page"));
            assertTrue(server.awaitRequest(5, TimeUnit.SECONDS));
            Future<File> second = workers.submit(() -> request(config, "deduplicated page"));

            Thread.sleep(150L);
            assertEquals(1, server.requestCount.get());
            server.releaseResponse();

            File firstResult = first.get(5, TimeUnit.SECONDS);
            File secondResult = second.get(5, TimeUnit.SECONDS);
            assertEquals(firstResult.getCanonicalFile(), secondResult.getCanonicalFile());
            assertArrayEquals(audio, Files.readAllBytes(firstResult.toPath()));
            assertEquals(1, server.requestCount.get());
        }
    }

    @Test
    public void cancellingAWaitingCallerDoesNotCancelTheGenerationOwner() throws Exception {
        byte[] audio = audioBytes(8192);
        try (FakeKokoroServer server = new FakeKokoroServer(
                FakeKokoroServer.Mode.BLOCK_BEFORE_RESPONSE, audio)) {
            TtsConfig config = TestFixtures.config(server.baseUrl());
            Future<File> owner = workers.submit(() -> request(config, "shared owner page"));
            assertTrue(server.awaitRequest(5, TimeUnit.SECONDS));

            KokoroAudioRepository.Cancellation waiterScope =
                    new KokoroAudioRepository.Cancellation();
            Future<File> waiter = workers.submit(() -> KokoroAudioRepository.requestSpeech(
                    context, config, 0, "", 0, "shared owner page", waiterScope, null));
            Thread.sleep(100L);
            waiterScope.cancel();

            assertFutureFailed(waiter);
            assertEquals(1, server.requestCount.get());
            server.releaseResponse();
            assertArrayEquals(audio, Files.readAllBytes(owner.get(5, TimeUnit.SECONDS).toPath()));
            assertEquals(1, server.requestCount.get());
        }
    }

    @Test
    public void cancellationDisconnectsSlowRequestAndPublishesNothing() throws Exception {
        byte[] audio = audioBytes(512 * 1024);
        try (FakeKokoroServer server = new FakeKokoroServer(FakeKokoroServer.Mode.SLOW_STREAM, audio)) {
            TtsConfig config = TestFixtures.config(server.baseUrl());
            KokoroAudioRepository.Cancellation cancellation = new KokoroAudioRepository.Cancellation();
            File target = KokoroAudioRepository.cacheFile(context, config, 0, "cancel me");
            Future<File> request = workers.submit(() -> KokoroAudioRepository.requestSpeech(
                    context, config, 0, "", 0, "cancel me", cancellation, null));

            assertTrue(server.awaitStreaming(5, TimeUnit.SECONDS));
            cancellation.cancel();

            assertFutureFailed(request);
            assertFalse(target.exists());
            File[] leftovers = KokoroAudioRepository.cacheDirectory(context).listFiles(
                    file -> file.getName().endsWith(".download"));
            assertTrue(leftovers == null || leftovers.length == 0);
        }
    }

    @Test
    public void truncatedFixedLengthResponseIsNeverPromoted() throws Exception {
        byte[] partial = audioBytes(1024);
        try (FakeKokoroServer server = new FakeKokoroServer(FakeKokoroServer.Mode.TRUNCATED, partial)) {
            TtsConfig config = TestFixtures.config(server.baseUrl());
            File target = KokoroAudioRepository.cacheFile(context, config, 0, "truncated");

            try {
                request(config, "truncated");
                fail("Expected the incomplete response to fail.");
            } catch (Exception expected) {
                assertFalse(target.exists());
            }
        }
    }

    @Test
    public void undersizedFilesAreNotAcceptedAsPlayableAudio() throws Exception {
        TtsConfig config = TestFixtures.config("http://127.0.0.1:1");
        File cache = KokoroAudioRepository.cacheFile(context, config, 0, "tiny");
        assertTrue(cache.getParentFile().mkdirs());
        Files.write(cache.toPath(), new byte[]{1, 2, 3, 4});

        assertEquals(
                null,
                KokoroAudioRepository.findPlayable(context, config, 0, "", 0, "tiny"));
    }

    private File request(TtsConfig config, String text) throws Exception {
        return KokoroAudioRepository.requestSpeech(
                context,
                config,
                0,
                "",
                0,
                text,
                new KokoroAudioRepository.Cancellation(),
                null);
    }

    private static void assertFutureFailed(Future<File> future) throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
            fail("Expected request to fail after cancellation.");
        } catch (ExecutionException expected) {
            assertNotNull(expected.getCause());
        }
    }

    private static byte[] audioBytes(int size) {
        byte[] bytes = new byte[Math.max(16, size)];
        Arrays.fill(bytes, (byte) 0x5a);
        return bytes;
    }
}
