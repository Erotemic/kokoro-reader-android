package com.crall.kokororeader;

import android.media.AudioManager;

/** Pure decisions used by the playback service and verified without a real audio device. */
final class PlaybackPolicy {
    enum AudioFocusDirective {
        GAIN,
        PAUSE_AND_RESUME,
        PAUSE_UNTIL_USER,
        IGNORE
    }

    private PlaybackPolicy() {
    }

    static AudioFocusDirective classifyAudioFocusChange(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            return AudioFocusDirective.GAIN;
        }
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            return AudioFocusDirective.PAUSE_AND_RESUME;
        }
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            return AudioFocusDirective.PAUSE_UNTIL_USER;
        }
        return AudioFocusDirective.IGNORE;
    }

    static boolean shouldAutoAdvance(boolean enabled, int completedPage, int pageCount) {
        return enabled && completedPage >= 0 && completedPage + 1 < pageCount;
    }

    static int pageOffsetToMilliseconds(int offsetUnits, int totalUnits, int durationMs) {
        if (durationMs <= 0 || totalUnits <= 0) {
            return 0;
        }
        int clamped = Math.max(0, Math.min(offsetUnits, totalUnits));
        int latestSafePosition = Math.max(0, durationMs - 250);
        return (int) Math.max(
                0L,
                Math.min(latestSafePosition, clamped * (long) durationMs / totalUnits));
    }
}
