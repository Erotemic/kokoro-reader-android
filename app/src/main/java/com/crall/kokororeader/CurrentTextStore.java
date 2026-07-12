package com.crall.kokororeader;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;

/** Crash-safe storage for the editor document; SharedPreferences is kept for small values only. */
final class CurrentTextStore {
    private static final String FILE_NAME = "current_text_session.json";

    private CurrentTextStore() {
    }

    static void write(Context context, String text) throws Exception {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("text", text == null ? "" : text);
        AtomicFileStore.writeJson(file(context), root);
    }

    static String read(Context context) throws Exception {
        JSONObject root = AtomicFileStore.readJson(file(context));
        return root == null ? "" : root.optString("text", "");
    }

    static void clear(Context context) {
        AtomicFileStore.delete(file(context));
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }
}
