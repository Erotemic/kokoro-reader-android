package com.crall.kokororeader;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Small bounded store for per-server TTS selections. */
final class TtsEndpointProfileStore {
    private static final String KEY = "ttsEndpointProfiles";
    private static final int LIMIT = 8;

    static final class Profile {
        final String server;
        final String model;
        final String voice;
        final String responseFormat;
        final boolean stream;
        final String langCode;

        Profile(
                String server,
                String model,
                String voice,
                String responseFormat,
                boolean stream,
                String langCode) {
            this.server = clean(server);
            this.model = clean(model);
            this.voice = clean(voice);
            this.responseFormat = clean(responseFormat);
            this.stream = stream;
            this.langCode = clean(langCode);
        }

        String label() {
            String detail = model;
            if (!voice.isEmpty()) {
                detail += detail.isEmpty() ? voice : " / " + voice;
            }
            return detail.isEmpty() ? server : server + "\n" + detail;
        }

        JSONObject toJson() throws Exception {
            JSONObject root = new JSONObject();
            root.put("server", server);
            root.put("model", model);
            root.put("voice", voice);
            root.put("responseFormat", responseFormat);
            root.put("stream", stream);
            root.put("langCode", langCode);
            return root;
        }

        static Profile fromJson(JSONObject root) {
            return new Profile(
                    root.optString("server", ""),
                    root.optString("model", ""),
                    root.optString("voice", ""),
                    root.optString("responseFormat", "mp3"),
                    root.optBoolean("stream", true),
                    root.optString("langCode", ""));
        }
    }

    private TtsEndpointProfileStore() {
    }

    static ArrayList<Profile> load(SharedPreferences prefs) {
        ArrayList<Profile> result = new ArrayList<>();
        String encoded = prefs.getString(KEY, "");
        if (encoded == null || encoded.trim().isEmpty()) {
            return result;
        }
        try {
            JSONArray array = new JSONArray(encoded);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                Profile profile = Profile.fromJson(item);
                if (!profile.server.isEmpty()) {
                    result.add(profile);
                }
            }
        } catch (Exception ignored) {
            // Corrupt profile history is non-critical; settings remain editable manually.
        }
        return result;
    }

    static void remember(SharedPreferences prefs, Profile profile) {
        if (profile == null || profile.server.isEmpty()) {
            return;
        }
        ArrayList<Profile> existing = load(prefs);
        ArrayList<Profile> updated = new ArrayList<>();
        updated.add(profile);
        for (Profile item : existing) {
            if (!profile.server.equals(item.server)) {
                updated.add(item);
            }
            if (updated.size() >= LIMIT) {
                break;
            }
        }
        JSONArray array = new JSONArray();
        try {
            for (Profile item : updated) {
                array.put(item.toJson());
            }
            prefs.edit().putString(KEY, array.toString()).apply();
        } catch (Exception ignored) {
            // The ordinary settings save must not fail because profile history failed.
        }
    }

    static List<String> servers(SharedPreferences prefs) {
        ArrayList<String> result = new ArrayList<>();
        for (Profile profile : load(prefs)) {
            result.add(profile.server);
        }
        return result;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
