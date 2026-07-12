package com.crall.kokororeader;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Locale;

/** Immutable Kokoro request settings captured when a playback document is started. */
final class TtsConfig {
    private static final String PREFS = "kokoro_reader_prefs";

    final String serverBase;
    final String model;
    final String voice;
    final double speed;
    final String responseFormat;
    final boolean stream;
    final String langCode;
    final boolean normalize;
    final boolean unitNormalization;
    final boolean urlNormalization;
    final boolean emailNormalization;
    final boolean pluralNormalization;
    final boolean phoneNormalization;

    TtsConfig(
            String serverBase,
            String model,
            String voice,
            double speed,
            String responseFormat,
            boolean stream,
            String langCode,
            boolean normalize,
            boolean unitNormalization,
            boolean urlNormalization,
            boolean emailNormalization,
            boolean pluralNormalization,
            boolean phoneNormalization) {
        this.serverBase = normalizeServer(serverBase);
        this.model = nonEmpty(model, "kokoro");
        this.voice = nonEmpty(voice, "af_bella");
        this.speed = clamp(speed, 0.25, 4.0);
        this.responseFormat = normalizeResponseFormat(responseFormat);
        this.stream = stream;
        this.langCode = langCode == null ? "" : langCode.trim();
        this.normalize = normalize;
        this.unitNormalization = unitNormalization;
        this.urlNormalization = urlNormalization;
        this.emailNormalization = emailNormalization;
        this.pluralNormalization = pluralNormalization;
        this.phoneNormalization = phoneNormalization;
    }

    static TtsConfig fromPreferences(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new TtsConfig(
                prefs.getString("server", BuildConfig.DEFAULT_SERVER_BASE),
                prefs.getString("model", "kokoro"),
                prefs.getString("voice", "af_bella"),
                parseDouble(prefs.getString("speed", "1.0"), 1.0),
                prefs.getString("responseFormat", "mp3"),
                prefs.getBoolean("stream", true),
                prefs.getString("langCode", ""),
                prefs.getBoolean("normalize", true),
                prefs.getBoolean("unitNorm", false),
                prefs.getBoolean("urlNorm", true),
                prefs.getBoolean("emailNorm", true),
                prefs.getBoolean("pluralNorm", true),
                prefs.getBoolean("phoneNorm", true));
    }

    static TtsConfig fromJson(JSONObject root) {
        if (root == null) {
            return null;
        }
        return new TtsConfig(
                root.optString("server", BuildConfig.DEFAULT_SERVER_BASE),
                root.optString("model", "kokoro"),
                root.optString("voice", "af_bella"),
                root.optDouble("speed", 1.0),
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

    JSONObject toJson() throws Exception {
        JSONObject root = new JSONObject();
        root.put("server", serverBase);
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

    String settingsKey() {
        return serverBase
                + "\nmodel=" + model
                + "\nvoice=" + voice
                + "\ntts_speed=" + speed
                + "\nformat=" + responseFormat
                + "\nstream=" + stream
                + "\nlang=" + langCode
                + "\nnormalize=" + normalize
                + "\nunit=" + unitNormalization
                + "\nurl=" + urlNormalization
                + "\nemail=" + emailNormalization
                + "\nplural=" + pluralNormalization
                + "\nphone=" + phoneNormalization;
    }

    JSONObject requestPayload(String text) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("model", model);
        payload.put("input", text);
        payload.put("voice", voice);
        payload.put("response_format", responseFormat);
        payload.put("speed", speed);
        payload.put("stream", stream);
        if (!langCode.isEmpty()) {
            payload.put("lang_code", langCode);
        }
        JSONObject normalizationOptions = new JSONObject();
        normalizationOptions.put("normalize", normalize);
        normalizationOptions.put("unit_normalization", unitNormalization);
        normalizationOptions.put("url_normalization", urlNormalization);
        normalizationOptions.put("email_normalization", emailNormalization);
        normalizationOptions.put("optional_pluralization_normalization", pluralNormalization);
        normalizationOptions.put("phone_normalization", phoneNormalization);
        payload.put("normalization_options", normalizationOptions);
        return payload;
    }

    static String normalizeResponseFormat(String value) {
        String format = value == null ? "" : value.trim().toLowerCase(Locale.US);
        switch (format) {
            case "opus":
            case "aac":
            case "flac":
            case "wav":
                return format;
            case "mp3":
            default:
                // Raw PCM has no container and cannot be passed directly to MediaPlayer.
                return "mp3";
        }
    }

    private static String normalizeServer(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.isEmpty() ? BuildConfig.DEFAULT_SERVER_BASE : result;
    }

    private static String nonEmpty(String value, String fallback) {
        String result = value == null ? "" : value.trim();
        return result.isEmpty() ? fallback : result;
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value == null ? "" : value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double clamp(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)) {
            return 1.0;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }
}
