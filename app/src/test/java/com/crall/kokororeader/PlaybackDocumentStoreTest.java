package com.crall.kokororeader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PlaybackDocumentStoreTest {
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        PlaybackDocumentStore.clear(context);
    }

    @After
    public void tearDown() {
        PlaybackDocumentStore.clear(context);
    }

    @Test
    public void roundTripFreezesPagesAndTtsConfiguration() throws Exception {
        ArrayList<String> mutablePages = new ArrayList<>(Arrays.asList("page one", "page two"));
        TtsConfig frozen = TestFixtures.config("http://127.0.0.1:9000");

        PlaybackDocumentStore.Document written = PlaybackDocumentStore.write(
                context, mutablePages, "session-1", 1, frozen);
        mutablePages.set(0, "mutated after write");
        context.getSharedPreferences("kokoro_reader_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("voice", "af_sky")
                .apply();

        PlaybackDocumentStore.Document restored = PlaybackDocumentStore.read(context);

        assertEquals(written.id, restored.id);
        assertEquals(Arrays.asList("page one", "page two"), restored.pages);
        assertEquals("session-1", restored.sessionId);
        assertEquals(1, restored.requestedPage);
        assertEquals(frozen.settingsKey(), restored.ttsConfig.settingsKey());
    }

    @Test
    public void identityIsStableForSameDocumentAndChangesWithContentOrSettings() throws Exception {
        ArrayList<String> pages = new ArrayList<>(Arrays.asList("same text"));
        TtsConfig base = TestFixtures.config("http://127.0.0.1:9000");

        String first = PlaybackDocumentStore.write(context, pages, "session", 0, base).id;
        String second = PlaybackDocumentStore.write(context, pages, "session", 0, base).id;
        String changedText = PlaybackDocumentStore.write(
                context,
                new ArrayList<>(Arrays.asList("different text")),
                "session",
                0,
                base).id;
        TtsConfig changedVoice = new TtsConfig(
                base.serverBase,
                base.model,
                "af_sky",
                base.speed,
                base.responseFormat,
                base.stream,
                base.langCode,
                base.normalize,
                base.unitNormalization,
                base.urlNormalization,
                base.emailNormalization,
                base.pluralNormalization,
                base.phoneNormalization);
        String changedSettings = PlaybackDocumentStore.write(
                context, pages, "session", 0, changedVoice).id;

        assertEquals(first, second);
        assertNotEquals(first, changedText);
        assertNotEquals(first, changedSettings);
    }

    @Test
    public void oldQueueCannotDeleteNewerReplacement() throws Exception {
        PlaybackDocumentStore.Document oldDocument = PlaybackDocumentStore.write(
                context,
                new ArrayList<>(Arrays.asList("old")),
                "old-session",
                0,
                TestFixtures.config("http://127.0.0.1:9000"));
        PlaybackDocumentStore.Document replacement = PlaybackDocumentStore.write(
                context,
                new ArrayList<>(Arrays.asList("new")),
                "new-session",
                0,
                TestFixtures.config("http://127.0.0.1:9000"));

        assertFalse(PlaybackDocumentStore.clearIfMatches(context, oldDocument.id));
        assertEquals(replacement.id, PlaybackDocumentStore.read(context).id);

        assertTrue(PlaybackDocumentStore.clearIfMatches(context, replacement.id));
        try {
            PlaybackDocumentStore.read(context);
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("Expected cleared playback document to be absent.");
    }
}
