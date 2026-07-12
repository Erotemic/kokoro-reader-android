package com.crall.kokororeader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PlaybackServiceTest {
    private Context context;
    private ServiceController<PlaybackService> controller;
    private PlaybackService service;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        PlaybackService.resetStaticStateForTests();
        PlaybackDocumentStore.clear(context);
        AtomicFileStore.deleteTree(KokoroAudioRepository.cacheDirectory(context));
        AtomicFileStore.deleteTree(new File(context.getFilesDir(), "kokoro_history"));
        controller = Robolectric.buildService(PlaybackService.class).create();
        service = controller.get();
    }

    @After
    public void tearDown() {
        controller.destroy();
        PlaybackService.resetStaticStateForTests();
        PlaybackDocumentStore.clear(context);
        AtomicFileStore.deleteTree(KokoroAudioRepository.cacheDirectory(context));
        AtomicFileStore.deleteTree(new File(context.getFilesDir(), "kokoro_history"));
    }

    @Test
    public void createsPlaybackNotificationChannel() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(PlaybackService.CHANNEL_ID);

        assertNotNull(channel);
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.getImportance());
        assertEquals("Speech playback", channel.getName().toString());
    }

    @Test
    public void nullRestartIsNonStickyAndDoesNotResurrectStoredDocument() throws Exception {
        PlaybackDocumentStore.Document document = PlaybackDocumentStore.write(
                context,
                new ArrayList<>(Arrays.asList("do not autoplay after process death")),
                "session",
                0,
                TestFixtures.config("http://127.0.0.1:9000"));

        int result = service.onStartCommand(null, 0, 41);

        assertEquals(Service.START_NOT_STICKY, result);
        assertEquals(document.id, PlaybackDocumentStore.read(context).id);
        assertFalse(PlaybackService.getSnapshot().isActive());
    }

    @Test
    public void missingPlaybackDocumentFailsClosed() {
        Intent play = new Intent(context, PlaybackService.class).setAction(PlaybackService.ACTION_PLAY);

        int result = service.onStartCommand(play, 0, 42);

        assertEquals(Service.START_NOT_STICKY, result);
        assertFalse(PlaybackService.getSnapshot().isActive());
        assertTrue(PlaybackService.getSnapshot().status.contains("Could not start background playback"));
    }

    @Test
    public void clearCacheCommandRemovesAllCachedAudio() throws Exception {
        File nested = new File(KokoroAudioRepository.cacheDirectory(context), "nested/audio.mp3");
        AtomicFileStore.writeText(nested, "more than sixteen bytes of cached audio");
        assertTrue(nested.isFile());

        Intent clear = new Intent(context, PlaybackService.class)
                .setAction(PlaybackService.ACTION_CLEAR_CACHE);
        int result = service.onStartCommand(clear, 0, 43);

        assertEquals(Service.START_NOT_STICKY, result);
        assertFalse(KokoroAudioRepository.cacheDirectory(context).exists());
        assertTrue(PlaybackService.getSnapshot().status.contains("Cleared"));
    }

    @Test
    public void clearHistoryCommandRemovesMetadataAndAudio() throws Exception {
        File metadata = new File(context.getFilesDir(), "kokoro_history/session/session.json");
        File audio = new File(context.getFilesDir(), "kokoro_history/session/audio/page.mp3");
        AtomicFileStore.writeText(metadata, "{\"sessionId\":\"session\"}");
        AtomicFileStore.writeText(audio, "more than sixteen bytes of history audio");

        Intent clear = new Intent(context, PlaybackService.class)
                .setAction(PlaybackService.ACTION_CLEAR_HISTORY);
        int result = service.onStartCommand(clear, 0, 44);

        assertEquals(Service.START_NOT_STICKY, result);
        assertFalse(new File(context.getFilesDir(), "kokoro_history").exists());
        assertTrue(PlaybackService.getSnapshot().status.contains("Cleared"));
    }
}
