package com.crall.kokororeader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class TtsEndpointDiscoveryTest {
    @Test
    public void wavhostModelsFilterToInstalledAndExposeSpeakers() throws Exception {
        String body = "{\"object\":\"list\",\"data\":["
                + "{\"id\":\"kokoro\",\"installed\":false,\"speakers\":[\"af_heart\"]},"
                + "{\"id\":\"qwen-0.6-customvoice\",\"installed\":true,"
                + "\"speakers\":[\"Ryan\",\"Aiden\"],\"default_voice\":\"Ryan\"}]}";

        ArrayList<TtsEndpointDiscovery.ModelOption> parsed = TtsEndpointDiscovery.parseModels(body);
        assertEquals(2, parsed.size());
        assertFalse(parsed.get(0).installed);
        assertTrue(parsed.get(1).installed);
        assertEquals("Ryan", parsed.get(1).defaultVoice);
        assertEquals(1, TtsEndpointDiscovery.usableModels(parsed).size());
        assertEquals("qwen-0.6-customvoice", TtsEndpointDiscovery.usableModels(parsed).get(0).id);
    }

    @Test
    public void qwenttsSingleModelWithoutInstalledFlagIsUsable() throws Exception {
        String body = "{\"object\":\"list\",\"data\":[{\"id\":\"qwen-0.6-customvoice-q8-ggml\"}]}";
        ArrayList<TtsEndpointDiscovery.ModelOption> parsed = TtsEndpointDiscovery.parseModels(body);
        assertEquals(1, TtsEndpointDiscovery.usableModels(parsed).size());
        assertTrue(parsed.get(0).installed);
    }

    @Test
    public void parsesQwenttsAndDataVoiceShapes() throws Exception {
        assertEquals(
                java.util.Arrays.asList("ryan", "aiden"),
                TtsEndpointDiscovery.parseVoices("{\"voices\":[{\"name\":\"ryan\"},{\"name\":\"aiden\"}]}")
        );
        assertEquals(
                java.util.Arrays.asList("af_bella", "af_sky"),
                TtsEndpointDiscovery.parseVoices("{\"data\":[{\"id\":\"af_bella\"},{\"name\":\"af_sky\"}]}")
        );
    }

    @Test
    public void modelVoicesMergeDefaultSpeakersAndEndpointVoices() {
        TtsEndpointDiscovery.ModelOption model = new TtsEndpointDiscovery.ModelOption(
                "qwen", true, java.util.Arrays.asList("Ryan", "Aiden"), "Ryan");
        assertEquals(
                java.util.Arrays.asList("Ryan", "Aiden", "saved-clone"),
                TtsEndpointDiscovery.voicesForModel(model, java.util.Arrays.asList("saved-clone"))
        );
        assertNull(TtsEndpointDiscovery.findModel(java.util.Collections.singletonList(model), "missing"));
    }
}
