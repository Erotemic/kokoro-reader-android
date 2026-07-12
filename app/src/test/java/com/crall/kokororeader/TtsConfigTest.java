package com.crall.kokororeader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class TtsConfigTest {
    @Test
    public void constructorNormalizesUnsafeAndInvalidValues() {
        TtsConfig config = new TtsConfig(
                "http://localhost:8880///",
                " ",
                "",
                Double.POSITIVE_INFINITY,
                "pcm",
                true,
                " en-us ",
                true,
                false,
                true,
                true,
                true,
                true);

        assertEquals("http://localhost:8880", config.serverBase);
        assertEquals("kokoro", config.model);
        assertEquals("af_bella", config.voice);
        assertEquals(1.0, config.speed, 0.0);
        assertEquals("mp3", config.responseFormat);
        assertEquals("en-us", config.langCode);
    }

    @Test
    public void speedIsClampedToSupportedRange() {
        assertEquals(0.25, configWithSpeed(-10.0).speed, 0.0);
        assertEquals(4.0, configWithSpeed(99.0).speed, 0.0);
        assertEquals(1.75, configWithSpeed(1.75).speed, 0.0);
    }

    @Test
    public void preferenceSnapshotDoesNotChangeAfterPreferencesAreEdited() {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("kokoro_reader_prefs", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .putString("server", "http://127.0.0.1:9000/")
                .putString("voice", "af_bella")
                .putString("speed", "1.25")
                .putString("responseFormat", "wav")
                .apply();

        TtsConfig captured = TtsConfig.fromPreferences(context);
        context.getSharedPreferences("kokoro_reader_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("voice", "af_sky")
                .putString("speed", "2.0")
                .apply();

        assertEquals("af_bella", captured.voice);
        assertEquals(1.25, captured.speed, 0.0);
        assertEquals("wav", captured.responseFormat);
        assertEquals("http://127.0.0.1:9000", captured.serverBase);
    }

    @Test
    public void jsonRoundTripPreservesFrozenSettings() throws Exception {
        TtsConfig original = new TtsConfig(
                "http://127.0.0.1:9000",
                "model-a",
                "af_sky",
                1.25,
                "wav",
                false,
                "a",
                false,
                true,
                false,
                true,
                false,
                true);

        TtsConfig restored = TtsConfig.fromJson(original.toJson());

        assertEquals(original.settingsKey(), restored.settingsKey());
    }

    @Test
    public void requestPayloadContainsAllNormalizationOptions() throws Exception {
        TtsConfig config = TestFixtures.config("http://127.0.0.1:8880");

        JSONObject payload = config.requestPayload("hello");
        JSONObject normalization = payload.getJSONObject("normalization_options");

        assertEquals("hello", payload.getString("input"));
        assertEquals("af_bella", payload.getString("voice"));
        assertEquals("mp3", payload.getString("response_format"));
        assertTrue(payload.getBoolean("stream"));
        assertTrue(normalization.getBoolean("normalize"));
        assertFalse(normalization.getBoolean("unit_normalization"));
        assertTrue(normalization.getBoolean("url_normalization"));
    }

    @Test
    public void cacheIdentityChangesForMeaningfulSettingChanges() {
        TtsConfig base = TestFixtures.config("http://127.0.0.1:8880");
        TtsConfig otherVoice = new TtsConfig(
                base.serverBase,
                base.model,
                "af_sky",
                base.speed,
                base.responseFormat,
                base.stream,
                base.langCode,
                base.normalize,
                base.unitNormalization,
                base.urlNormalization,
                base.emailNormalization,
                base.pluralNormalization,
                base.phoneNormalization);

        assertNotEquals(base.settingsKey(), otherVoice.settingsKey());
        assertNotEquals(
                KokoroAudioRepository.audioFileName(base, 0, "hello"),
                KokoroAudioRepository.audioFileName(otherVoice, 0, "hello"));
    }

    private static TtsConfig configWithSpeed(double speed) {
        return new TtsConfig(
                "http://127.0.0.1:8880",
                "kokoro",
                "af_bella",
                speed,
                "mp3",
                true,
                "",
                true,
                false,
                true,
                true,
                true,
                true);
    }
}
