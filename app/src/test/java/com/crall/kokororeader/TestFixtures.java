package com.crall.kokororeader;

final class TestFixtures {
    private TestFixtures() {
    }

    static TtsConfig config(String serverBase) {
        return new TtsConfig(
                serverBase,
                "kokoro",
                "af_bella",
                1.0,
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
