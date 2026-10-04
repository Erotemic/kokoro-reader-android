package com.crall.kokororeader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Parsers and small policies for OpenAI-compatible TTS endpoint discovery. */
final class TtsEndpointDiscovery {
    static final class ModelOption {
        final String id;
        final boolean installed;
        final ArrayList<String> speakers;
        final String defaultVoice;

        ModelOption(String id, boolean installed, List<String> speakers, String defaultVoice) {
            this.id = id;
            this.installed = installed;
            this.speakers = new ArrayList<>(speakers);
            this.defaultVoice = defaultVoice == null ? "" : defaultVoice.trim();
        }
    }

    private TtsEndpointDiscovery() {
    }

    static ArrayList<ModelOption> parseModels(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONArray data = root.optJSONArray("data");
        if (data == null) {
            throw new IllegalStateException("No model list found in /v1/models response.");
        }
        ArrayList<ModelOption> result = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String id = item.optString("id", "").trim();
            if (id.isEmpty()) {
                continue;
            }
            // Wavhost exposes its entire registry and marks locally usable entries
            // with installed=true. Other OpenAI-compatible servers often omit the
            // field, in which case an advertised model is assumed usable.
            boolean installed = !item.has("installed") || item.optBoolean("installed", false);
            ArrayList<String> speakers = strings(item.optJSONArray("speakers"));
            String defaultVoice = item.optString("default_voice", "").trim();
            result.add(new ModelOption(id, installed, speakers, defaultVoice));
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("No models found in /v1/models response.");
        }
        return result;
    }

    static ArrayList<ModelOption> usableModels(List<ModelOption> models) {
        ArrayList<ModelOption> result = new ArrayList<>();
        for (ModelOption model : models) {
            if (model.installed) {
                result.add(model);
            }
        }
        return result;
    }

    static ArrayList<String> parseVoices(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONArray data = root.optJSONArray("voices");
        if (data == null) {
            data = root.optJSONArray("data");
        }
        if (data == null) {
            throw new IllegalStateException("No voice list found in response.");
        }
        ArrayList<String> result = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            Object item = data.opt(i);
            if (item instanceof String) {
                addNonEmpty(result, (String) item);
            } else if (item instanceof JSONObject) {
                JSONObject voice = (JSONObject) item;
                addNonEmpty(result, voice.optString("id", voice.optString("name", "")));
            }
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("No voices found in response.");
        }
        return result;
    }

    static ArrayList<String> voicesForModel(ModelOption model, List<String> endpointVoices) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        if (model != null) {
            addVoice(merged, model.defaultVoice);
            for (String voice : model.speakers) {
                addVoice(merged, voice);
            }
        }
        if (endpointVoices != null) {
            for (String voice : endpointVoices) {
                addVoice(merged, voice);
            }
        }
        return new ArrayList<>(merged.values());
    }

    static ModelOption findModel(List<ModelOption> models, String id) {
        if (id == null) {
            return null;
        }
        for (ModelOption model : models) {
            if (id.equals(model.id)) {
                return model;
            }
        }
        return null;
    }


    private static void addVoice(LinkedHashMap<String, String> target, String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isEmpty()) {
            target.putIfAbsent(normalized.toLowerCase(java.util.Locale.US), normalized);
        }
    }

    private static ArrayList<String> strings(JSONArray array) {
        ArrayList<String> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (int i = 0; i < array.length(); i++) {
            addNonEmpty(result, array.optString(i, ""));
        }
        return result;
    }

    private static void addNonEmpty(List<String> target, String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isEmpty() && !target.contains(normalized)) {
            target.add(normalized);
        }
    }
}
