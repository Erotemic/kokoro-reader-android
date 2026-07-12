package com.crall.kokororeader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
import java.nio.charset.StandardCharsets;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AtomicFileStoreTest {
    private Context context;
    private File root;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        root = new File(context.getFilesDir(), "atomic_store_test");
        AtomicFileStore.deleteTree(root);
        assertTrue(root.mkdirs());
    }

    @After
    public void tearDown() {
        AtomicFileStore.deleteTree(root);
    }

    @Test
    public void jsonMutationRoundTripsWithoutLosingExistingFields() throws Exception {
        File file = new File(root, "state.json");
        JSONObject initial = new JSONObject();
        initial.put("documentId", "old");
        initial.put("page", 3);
        AtomicFileStore.writeJson(file, initial);

        AtomicFileStore.mutateJson(file, value -> {
            value.put("page", 4);
            value.put("audioCount", 2);
        });

        JSONObject restored = AtomicFileStore.readJson(file);
        assertEquals("old", restored.getString("documentId"));
        assertEquals(4, restored.getInt("page"));
        assertEquals(2, restored.getInt("audioCount"));
    }

    @Test
    public void copyPublishesTheCompleteSource() throws Exception {
        File source = new File(root, "source.bin");
        File destination = new File(root, "nested/destination.bin");
        byte[] bytes = "complete generated audio bytes".getBytes(StandardCharsets.UTF_8);
        AtomicFileStore.writeText(source, new String(bytes, StandardCharsets.UTF_8));

        AtomicFileStore.copy(source, destination);

        assertArrayEquals(bytes, AtomicFileStore.readBytes(destination));
        assertFalse(new File(destination.getAbsolutePath() + ".new").exists());
    }

    @Test
    public void conditionalDeleteCannotEraseAReplacementDocument() throws Exception {
        File file = new File(root, "active.json");
        AtomicFileStore.writeJson(file, new JSONObject().put("id", "document-b"));

        assertFalse(AtomicFileStore.deleteIfJsonStringEquals(file, "id", "document-a"));
        assertTrue(file.isFile());
        assertEquals("document-b", AtomicFileStore.readJson(file).getString("id"));

        assertTrue(AtomicFileStore.deleteIfJsonStringEquals(file, "id", "document-b"));
        assertFalse(file.exists());
    }

    @Test
    public void deleteTreeRemovesAtomicSidecarsEvenWhenBaseIsMissing() throws Exception {
        File base = new File(root, "orphan.json");
        File backup = new File(base.getAbsolutePath() + ".bak");
        File pending = new File(base.getAbsolutePath() + ".new");
        AtomicFileStore.writeText(backup, "backup");
        AtomicFileStore.writeText(pending, "pending");

        int deleted = AtomicFileStore.deleteTree(base);

        assertEquals(2, deleted);
        assertFalse(backup.exists());
        assertFalse(pending.exists());
    }

    @Test
    public void currentTextStoreUsesCrashSafeAppPrivateStorage() throws Exception {
        CurrentTextStore.clear(context);

        CurrentTextStore.write(context, "private article text");
        assertEquals("private article text", CurrentTextStore.read(context));

        CurrentTextStore.clear(context);
        assertEquals("", CurrentTextStore.read(context));
    }
}
