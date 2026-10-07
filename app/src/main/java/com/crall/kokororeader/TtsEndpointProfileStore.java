package com.crall.kokororeader;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small bounded store for named endpoint-specific TTS request profiles. */
final class TtsEndpointProfileStore {
    private static final String KEY = "ttsEndpointProfiles";
    private static final int LIMIT = 12;

    static final class Profile {
        final String name;
        final String server;
        final String model;
        final String voice;
        final String speed;
        final String responseFormat;
        final boolean stream;
        final String langCode;
        final boolean normalize;
        final boolean unitNormalization;
        final boolean urlNormalization;
        final boolean emailNormalization;
        final boolean pluralNormalization;
        final boolean phoneNormalization;

        /** Backward-compatible constructor for profiles stored by older app versions/tests. */
        Profile(
                String server,
                String model,
                String voice,
                String responseFormat,
                boolean stream,
                String langCode) {
            this(
                    suggestName(server, model),
                    server,
                    model,
                    voice,
                    "1.0",
                    responseFormat,
                    stream,
                    langCode,
                    true,
                    false,
                    true,
                    true,
                    true,
                    true);
        }

        Profile(
                String name,
                String server,
                String model,
                String voice,
                String speed,
                String responseFormat,
                boolean stream,
                String langCode,
                boolean normalize,
                boolean unitNormalization,
                boolean urlNormalization,
                boolean emailNormalization,
                boolean pluralNormalization,
                boolean phoneNormalization) {
            this.name = clean(name).isEmpty() ? suggestName(server, model) : clean(name);
            this.server = cleanServer(server);
            this.model = clean(model);
            this.voice = clean(voice);
            this.speed = normalizeSpeed(speed);
            this.responseFormat = TtsConfig.normalizeResponseFormat(responseFormat);
            this.stream = stream;
            this.langCode = clean(langCode);
            this.normalize = normalize;
            this.unitNormalization = unitNormalization;
            this.urlNormalization = urlNormalization;
            this.emailNormalization = emailNormalization;
            this.pluralNormalization = pluralNormalization;
            this.phoneNormalization = phoneNormalization;
        }

        String label() {
            String detail = model;
            if (!voice.isEmpty()) {
                detail += detail.isEmpty() ? voice : " / " + voice;
            }
            if (!responseFormat.isEmpty()) {
                detail += detail.isEmpty() ? responseFormat : " • " + responseFormat;
            }
            String endpoint = server;
            if (!detail.isEmpty()) {
                endpoint += "\n" + detail;
            }
            return name + "\n" + endpoint;
        }

        boolean sameRequestSettings(Profile other) {
            if (other == null) {
                return false;
            }
            return server.equals(other.server)
                    && model.equals(other.model)
                    && voice.equals(other.voice)
                    && speed.equals(other.speed)
                    && responseFormat.equals(other.responseFormat)
                    && stream == other.stream
                    && langCode.equals(other.langCode)
                    && normalize == other.normalize
                    && unitNormalization == other.unitNormalization
                    && urlNormalization == other.urlNormalization
                    && emailNormalization == other.emailNormalization
                    && pluralNormalization == other.pluralNormalization
                    && phoneNormalization == other.phoneNormalization;
        }

        JSONObject toJson() throws Exception {
            JSONObject root = new JSONObject();
            root.put("name", name);
            root.put("server", server);
            root.put("model", model);
            root.put("voice", voice);
            root.put("speed", speed);
            root.put("responseFormat", responseFormat);
            root.put("stream", stream);
            root.put("langCode", langCode);
            root.put("normalize", normalize);
            root.put("unitNorm", unitNormalization);
            root.put("urlNorm", urlNormalization);
            root.put("emailNorm", emailNormalization);
            root.put("pluralNorm", pluralNormalization);
            root.put("phoneNorm", phoneNormalization);
            return root;
        }

        static Profile fromJson(JSONObject root) {
            String server = root.optString("server", "");
            String model = root.optString("model", "");
            return new Profile(
                    root.optString("name", suggestName(server, model)),
                    server,
                    model,
                    root.optString("voice", ""),
                    root.optString("speed", "1.0"),
                    root.optString("responseFormat", "mp3"),
                    root.optBoolean("stream", true),
                    root.optString("langCode", ""),
                    root.optBoolean("normalize", true),
                    root.optBoolean("unitNorm", false),
                    root.optBoolean("urlNorm", true),
                    root.optBoolean("emailNorm", true),
                    root.optBoolean("pluralNorm", true),
                    root.optBoolean("phoneNorm", true));
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

    /** Insert or update a profile by its user-facing name, moving it to the top. */
    static void remember(SharedPreferences prefs, Profile profile) {
        if (profile == null || profile.server.isEmpty()) {
            return;
        }
        ArrayList<Profile> existing = load(prefs);
        ArrayList<Profile> updated = new ArrayList<>();
        updated.add(profile);
        for (Profile item : existing) {
            if (!sameName(profile.name, item.name)) {
                updated.add(item);
            }
            if (updated.size() >= LIMIT) {
                break;
            }
        }
        save(prefs, updated);
    }

    static boolean delete(SharedPreferences prefs, String name) {
        ArrayList<Profile> existing = load(prefs);
        ArrayList<Profile> updated = new ArrayList<>();
        boolean removed = false;
        for (Profile item : existing) {
            if (sameName(name, item.name)) {
                removed = true;
            } else {
                updated.add(item);
            }
        }
        if (removed) {
            save(prefs, updated);
        }
        return removed;
    }

    static Profile findByName(SharedPreferences prefs, String name) {
        if (clean(name).isEmpty()) {
            return null;
        }
        for (Profile profile : load(prefs)) {
            if (sameName(name, profile.name)) {
                return profile;
            }
        }
        return null;
    }

    static Profile findMatching(SharedPreferences prefs, Profile candidate) {
        if (candidate == null) {
            return null;
        }
        for (Profile profile : load(prefs)) {
            if (profile.sameRequestSettings(candidate)) {
                return profile;
            }
        }
        return null;
    }

    static List<String> servers(SharedPreferences prefs) {
        ArrayList<String> result = new ArrayList<>();
        for (Profile profile : load(prefs)) {
            if (!result.contains(profile.server)) {
                result.add(profile.server);
            }
        }
        return result;
    }

    static String suggestName(String server, String model) {
        String modelPart = clean(model);
        if (modelPart.isEmpty()) {
            modelPart = "TTS";
        }
        String endpoint = cleanServer(server);
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host != null && !host.isEmpty()) {
                return modelPart + " @ " + host + (port > 0 ? ":" + port : "");
            }
        } catch (Exception ignored) {
            // Fall through to the raw endpoint below.
        }
        return endpoint.isEmpty() ? modelPart : modelPart + " @ " + endpoint;
    }

    private static void save(SharedPreferences prefs, List<Profile> profiles) {
        JSONArray array = new JSONArray();
        try {
            for (Profile item : profiles) {
                array.put(item.toJson());
            }
            prefs.edit().putString(KEY, array.toString()).apply();
        } catch (Exception ignored) {
            // Ordinary settings should not fail because profile history failed.
        }
    }

    private static boolean sameName(String a, String b) {
        return clean(a).toLowerCase(Locale.US).equals(clean(b).toLowerCase(Locale.US));
    }

    private static String normalizeSpeed(String value) {
        try {
            double parsed = Double.parseDouble(clean(value));
            if (!Double.isFinite(parsed)) {
                return "1.0";
            }
            double clamped = Math.max(0.25, Math.min(4.0, parsed));
            return Double.toString(clamped);
        } catch (Exception ignored) {
            return "1.0";
        }
    }

    private static String cleanServer(String value) {
        String out = clean(value);
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
