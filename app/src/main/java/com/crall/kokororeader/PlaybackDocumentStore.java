package com.crall.kokororeader;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;

/** Durable, crash-safe handoff between the UI and the background playback service. */
final class PlaybackDocumentStore {
    private static final String FILE_NAME = "active_playback_document.json";

    static final class Document {
        final String id;
        final ArrayList<String> pages;
        final String sessionId;
        final int requestedPage;
        final TtsConfig ttsConfig;

        Document(
                String id,
                ArrayList<String> pages,
                String sessionId,
                int requestedPage,
                TtsConfig ttsConfig) {
            this.id = id;
            this.pages = pages;
            this.sessionId = sessionId == null ? "" : sessionId;
            this.requestedPage = requestedPage;
            this.ttsConfig = ttsConfig;
        }
    }

    private PlaybackDocumentStore() {
    }

    static Document write(
            Context context,
            ArrayList<String> pages,
            String sessionId,
            int requestedPage,
            TtsConfig ttsConfig) throws Exception {
        ArrayList<String> copy = new ArrayList<>(pages);
        TtsConfig frozenConfig = ttsConfig == null ? TtsConfig.fromPreferences(context) : ttsConfig;
        String id = documentId(copy, sessionId, frozenConfig);
        JSONObject root = new JSONObject();
        root.put("version", 2);
        root.put("id", id);
        root.put("sessionId", sessionId == null ? "" : sessionId);
        root.put("requestedPage", requestedPage);
        root.put("ttsConfig", frozenConfig.toJson());
        JSONArray pageArray = new JSONArray();
        for (String page : copy) {
            pageArray.put(page);
        }
        root.put("pages", pageArray);
        AtomicFileStore.writeJson(file(context), root);
        return new Document(id, copy, sessionId, requestedPage, frozenConfig);
    }

    static Document read(Context context) throws Exception {
        File source = file(context);
        JSONObject root = AtomicFileStore.readJson(source);
        if (root == null) {
            throw new IllegalStateException("No active playback document is available.");
        }
        JSONArray pageArray = root.getJSONArray("pages");
        ArrayList<String> pages = new ArrayList<>(pageArray.length());
        for (int index = 0; index < pageArray.length(); index++) {
            pages.add(pageArray.getString(index));
        }
        String sessionId = root.optString("sessionId", "");
        TtsConfig config = TtsConfig.fromJson(root.optJSONObject("ttsConfig"));
        if (config == null) {
            // Compatibility with documents written by v0.6.0 before settings were frozen.
            config = TtsConfig.fromPreferences(context);
        }
        String id = root.optString("id", documentId(pages, sessionId, config));
        int requestedPage = root.optInt("requestedPage", 0);
        return new Document(id, pages, sessionId, requestedPage, config);
    }

    static void clear(Context context) {
        AtomicFileStore.delete(file(context));
    }

    static boolean clearIfMatches(Context context, String expectedDocumentId) {
        if (expectedDocumentId == null || expectedDocumentId.isEmpty()) {
            return false;
        }
        try {
            return AtomicFileStore.deleteIfJsonStringEquals(
                    file(context), "id", expectedDocumentId);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static String documentId(ArrayList<String> pages, String sessionId, TtsConfig config) {
        StringBuilder material = new StringBuilder(sessionId == null ? "" : sessionId);
        material.append('\n').append(config.settingsKey());
        material.append('\n').append(pages.size());
        for (String page : pages) {
            material.append('\n').append(page.length()).append(':').append(page);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(material.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : bytes) {
                hex.append(String.format(Locale.US, "%02x", value));
            }
            return hex.substring(0, 24);
        } catch (Exception ignored) {
            return Integer.toHexString(material.toString().hashCode());
        }
    }
}
