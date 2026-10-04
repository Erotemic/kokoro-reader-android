package com.crall.kokororeader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class TtsEndpointProfileStoreTest {
    @Test
    public void remembersDistinctEndpointsAndReplacesSameServer() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences("endpoint-profile-test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();

        TtsEndpointProfileStore.remember(prefs, new TtsEndpointProfileStore.Profile(
                "http://host:8880", "kokoro", "af_bella", "mp3", true, ""));
        TtsEndpointProfileStore.remember(prefs, new TtsEndpointProfileStore.Profile(
                "http://host:11436", "qwen-q8", "ryan", "wav", true, ""));
        TtsEndpointProfileStore.remember(prefs, new TtsEndpointProfileStore.Profile(
                "http://host:8880", "kokoro", "af_sky", "mp3", true, ""));

        java.util.ArrayList<TtsEndpointProfileStore.Profile> profiles = TtsEndpointProfileStore.load(prefs);
        assertEquals(2, profiles.size());
        assertEquals("http://host:8880", profiles.get(0).server);
        assertEquals("af_sky", profiles.get(0).voice);
        assertEquals("http://host:11436", profiles.get(1).server);
        assertEquals("wav", profiles.get(1).responseFormat);
        assertFalse(profiles.get(0).label().isEmpty());
    }
}
