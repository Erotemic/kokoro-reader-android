package com.crall.kokororeader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class TtsEndpointProfileStoreTest {
    private SharedPreferences freshPrefs(String name) {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        return prefs;
    }

    private TtsEndpointProfileStore.Profile profile(
            String name, String server, String model, String voice, String format) {
        return new TtsEndpointProfileStore.Profile(
                name,
                server,
                model,
                voice,
                "1.15",
                format,
                true,
                "English",
                true,
                false,
                true,
                true,
                true,
                true);
    }

    @Test
    public void namedProfilesCanCoexistOnSameServer() {
        SharedPreferences prefs = freshPrefs("endpoint-profile-same-server-test");

        TtsEndpointProfileStore.remember(prefs, profile(
                "Kokoro MP3", "http://host:8880", "kokoro", "af_bella", "mp3"));
        TtsEndpointProfileStore.remember(prefs, profile(
                "Kokoro WAV test", "http://host:8880", "kokoro", "af_sky", "wav"));

        java.util.ArrayList<TtsEndpointProfileStore.Profile> profiles = TtsEndpointProfileStore.load(prefs);
        assertEquals(2, profiles.size());
        assertEquals("Kokoro WAV test", profiles.get(0).name);
        assertEquals("Kokoro MP3", profiles.get(1).name);
    }

    @Test
    public void sameNameUpdatesInPlaceAndPreservesFullEndpointContract() {
        SharedPreferences prefs = freshPrefs("endpoint-profile-update-test");

        TtsEndpointProfileStore.remember(prefs, profile(
                "Lecture server", "http://host:8880", "kokoro", "af_bella", "mp3"));
        TtsEndpointProfileStore.remember(prefs, new TtsEndpointProfileStore.Profile(
                "Lecture server",
                "http://host:11436/",
                "qwen-q8",
                "ryan",
                "0.95",
                "wav",
                false,
                "English",
                false,
                true,
                false,
                true,
                false,
                true));

        java.util.ArrayList<TtsEndpointProfileStore.Profile> profiles = TtsEndpointProfileStore.load(prefs);
        assertEquals(1, profiles.size());
        TtsEndpointProfileStore.Profile restored = profiles.get(0);
        assertEquals("Lecture server", restored.name);
        assertEquals("http://host:11436", restored.server);
        assertEquals("qwen-q8", restored.model);
        assertEquals("ryan", restored.voice);
        assertEquals("0.95", restored.speed);
        assertEquals("wav", restored.responseFormat);
        assertFalse(restored.stream);
        assertFalse(restored.normalize);
        assertTrue(restored.unitNormalization);
        assertFalse(restored.urlNormalization);
        assertTrue(restored.emailNormalization);
        assertFalse(restored.pluralNormalization);
        assertTrue(restored.phoneNormalization);
    }

    @Test
    public void legacyProfilesMigrateWithSuggestedNameAndDefaults() throws Exception {
        SharedPreferences prefs = freshPrefs("endpoint-profile-migration-test");
        JSONObject legacy = new JSONObject();
        legacy.put("server", "http://host:8880");
        legacy.put("model", "kokoro");
        legacy.put("voice", "af_bella");
        legacy.put("responseFormat", "mp3");
        legacy.put("stream", true);
        legacy.put("langCode", "");
        prefs.edit().putString("ttsEndpointProfiles", new JSONArray().put(legacy).toString()).commit();

        TtsEndpointProfileStore.Profile restored = TtsEndpointProfileStore.load(prefs).get(0);
        assertFalse(restored.name.isEmpty());
        assertEquals("1.0", restored.speed);
        assertTrue(restored.normalize);
        assertFalse(restored.unitNormalization);
    }

    @Test
    public void findMatchingIgnoresProfileNameButChecksRequestSettings() {
        SharedPreferences prefs = freshPrefs("endpoint-profile-match-test");
        TtsEndpointProfileStore.Profile stored = profile(
                "Kokoro", "http://host:8880", "kokoro", "af_bella", "mp3");
        TtsEndpointProfileStore.remember(prefs, stored);

        TtsEndpointProfileStore.Profile candidate = profile(
                "Anything", "http://host:8880/", "kokoro", "af_bella", "mp3");
        TtsEndpointProfileStore.Profile match = TtsEndpointProfileStore.findMatching(prefs, candidate);
        assertNotNull(match);
        assertEquals("Kokoro", match.name);

        assertNull(TtsEndpointProfileStore.findMatching(
                prefs,
                profile("Anything", "http://host:8880", "kokoro", "af_bella", "wav")));
    }

    @Test
    public void deleteRemovesOnlyNamedProfile() {
        SharedPreferences prefs = freshPrefs("endpoint-profile-delete-test");
        TtsEndpointProfileStore.remember(prefs, profile(
                "Kokoro", "http://host:8880", "kokoro", "af_bella", "mp3"));
        TtsEndpointProfileStore.remember(prefs, profile(
                "Qwen", "http://host:11436", "qwen-q8", "ryan", "wav"));

        assertTrue(TtsEndpointProfileStore.delete(prefs, "Kokoro"));
        assertFalse(TtsEndpointProfileStore.delete(prefs, "missing"));
        assertNull(TtsEndpointProfileStore.findByName(prefs, "Kokoro"));
        assertNotNull(TtsEndpointProfileStore.findByName(prefs, "Qwen"));
    }
}
