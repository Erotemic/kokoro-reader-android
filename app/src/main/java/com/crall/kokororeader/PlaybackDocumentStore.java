package com.crall.kokororeader;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;

/** Durable handoff between the UI and the background playback service. */
final class PlaybackDocumentStore {
    private static final String FILE_NAME = "active_playback_document.json";

    static final class Document {
        final String id;
        final ArrayList<String> pages;
        final String sessionId;
        final int requestedPage;

        Document(String id, ArrayList<String> pages, String sessionId, int requestedPage) {
            this.id = id;
            this.pages = pages;
            this.sessionId = sessionId == null ? "" : sessionId;
            this.requestedPage = requestedPage;
        }
    }

    private PlaybackDocumentStore() {
    }

    static Document write(Context context, ArrayList<String> pages, String sessionId, int requestedPage) throws Exception {
        ArrayList<String> copy = new ArrayList<>(pages);
        String id = documentId(copy, sessionId);
        JSONObject root = new JSONObject();
        root.put("id", id);
        root.put("sessionId", sessionId == null ? "" : sessionId);
        root.put("requestedPage", requestedPage);
        JSONArray pageArray = new JSONArray();
        for (String page : copy) {
            pageArray.put(page);
        }
        root.put("pages", pageArray);

        File target = file(context);
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(tmp)) {
            output.write(root.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("Could not replace active playback document.");
        }
        if (!tmp.renameTo(target)) {
            throw new IllegalStateException("Could not publish active playback document.");
        }
        return new Document(id, copy, sessionId, requestedPage);
    }

    static Document read(Context context) throws Exception {
        File source = file(context);
        if (!source.isFile()) {
            throw new IllegalStateException("No active playback document is available.");
        }
        byte[] data;
        try (FileInputStream input = new FileInputStream(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            data = output.toByteArray();
        }
        JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
        JSONArray pageArray = root.getJSONArray("pages");
        ArrayList<String> pages = new ArrayList<>(pageArray.length());
        for (int index = 0; index < pageArray.length(); index++) {
            pages.add(pageArray.getString(index));
        }
        String sessionId = root.optString("sessionId", "");
        String id = root.optString("id", documentId(pages, sessionId));
        int requestedPage = root.optInt("requestedPage", 0);
        return new Document(id, pages, sessionId, requestedPage);
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static String documentId(ArrayList<String> pages, String sessionId) {
        StringBuilder material = new StringBuilder(sessionId == null ? "" : sessionId);
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
