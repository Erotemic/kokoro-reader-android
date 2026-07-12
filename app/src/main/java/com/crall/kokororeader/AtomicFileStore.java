package com.crall.kokororeader;

import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Process-wide serialization and crash-safe replacement for app-owned files. */
final class AtomicFileStore {
    interface JsonMutation {
        void apply(JSONObject value) throws Exception;
    }

    private AtomicFileStore() {
    }

    static synchronized byte[] readBytes(File file) throws Exception {
        AtomicFile atomicFile = new AtomicFile(file);
        try (FileInputStream input = atomicFile.openRead();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    static synchronized String readText(File file) throws Exception {
        return new String(readBytes(file), StandardCharsets.UTF_8);
    }

    static synchronized JSONObject readJson(File file) throws Exception {
        if (!file.isFile()) {
            return null;
        }
        return new JSONObject(readText(file));
    }

    static synchronized void writeText(File file, String text) throws Exception {
        writeBytes(file, text.getBytes(StandardCharsets.UTF_8));
    }

    static synchronized void writeJson(File file, JSONObject value) throws Exception {
        writeText(file, value.toString(2));
    }

    static synchronized void mutateJson(File file, JsonMutation mutation) throws Exception {
        JSONObject value = file.isFile() ? readJson(file) : new JSONObject();
        mutation.apply(value);
        writeJson(file, value);
    }

    static synchronized void delete(File file) {
        // AtomicFile may leave a backup or pending write beside the base file.
        // Delete all three while holding the same process-wide lock as reads/writes.
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(file.getAbsolutePath() + ".bak").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(file.getAbsolutePath() + ".new").delete();
    }

    static synchronized int deleteTree(File file) {
        if (file == null) {
            return 0;
        }
        int deleted = 0;
        if (file.exists()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleted += deleteTree(child);
                }
            }
            if (file.delete()) {
                deleted++;
            }
        }
        // AtomicFile sidecars may remain even when the base path is absent or was replaced.
        File backup = new File(file.getAbsolutePath() + ".bak");
        if (backup.delete()) {
            deleted++;
        }
        File pending = new File(file.getAbsolutePath() + ".new");
        if (pending.delete()) {
            deleted++;
        }
        return deleted;
    }

    static synchronized boolean deleteIfJsonStringEquals(
            File file, String key, String expectedValue) throws Exception {
        if (!file.isFile()) {
            delete(file);
            return true;
        }
        JSONObject value = readJson(file);
        String actual = value == null ? "" : value.optString(key, "");
        if (!actual.equals(expectedValue == null ? "" : expectedValue)) {
            return false;
        }
        delete(file);
        return true;
    }

    static synchronized void copy(File source, File destination) throws Exception {
        if (source.getCanonicalFile().equals(destination.getCanonicalFile())) {
            return;
        }
        ensureParent(destination);
        AtomicFile atomicFile = new AtomicFile(destination);
        FileOutputStream output = null;
        try (FileInputStream input = new FileInputStream(source)) {
            output = atomicFile.startWrite();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
            output.getFD().sync();
            atomicFile.finishWrite(output);
        } catch (Exception ex) {
            if (output != null) {
                atomicFile.failWrite(output);
            }
            throw ex;
        }
    }

    private static void writeBytes(File file, byte[] bytes) throws Exception {
        ensureParent(file);
        AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            output = atomicFile.startWrite();
            output.write(bytes);
            output.flush();
            output.getFD().sync();
            atomicFile.finishWrite(output);
        } catch (Exception ex) {
            if (output != null) {
                atomicFile.failWrite(output);
            }
            throw ex;
        }
    }

    private static void ensureParent(File file) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create directory: " + parent.getAbsolutePath());
        }
    }
}
