package com.crall.kokororeader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PlaybackPolicyTest {
    @Test
    public void transientFocusLossPausesAndResumes() {
        assertEquals(
                PlaybackPolicy.AudioFocusDirective.PAUSE_AND_RESUME,
                PlaybackPolicy.classifyAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT));
        assertEquals(
                PlaybackPolicy.AudioFocusDirective.PAUSE_AND_RESUME,
                PlaybackPolicy.classifyAudioFocusChange(
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK));
    }

    @Test
    public void permanentFocusLossRequiresUserResume() {
        assertEquals(
                PlaybackPolicy.AudioFocusDirective.PAUSE_UNTIL_USER,
                PlaybackPolicy.classifyAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS));
        assertEquals(
                PlaybackPolicy.AudioFocusDirective.GAIN,
                PlaybackPolicy.classifyAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN));
    }

    @Test
    public void autoAdvanceRequiresBothPreferenceAndFollowingPage() {
        assertTrue(PlaybackPolicy.shouldAutoAdvance(true, 0, 2));
        assertFalse(PlaybackPolicy.shouldAutoAdvance(false, 0, 2));
        assertFalse(PlaybackPolicy.shouldAutoAdvance(true, 1, 2));
        assertFalse(PlaybackPolicy.shouldAutoAdvance(true, -1, 2));
    }

    @Test
    public void pageOffsetConversionClampsToPlayableRange() {
        assertEquals(5000, PlaybackPolicy.pageOffsetToMilliseconds(50, 100, 10000));
        assertEquals(0, PlaybackPolicy.pageOffsetToMilliseconds(-1, 100, 10000));
        assertEquals(9750, PlaybackPolicy.pageOffsetToMilliseconds(1000, 100, 10000));
        assertEquals(0, PlaybackPolicy.pageOffsetToMilliseconds(5, 0, 10000));
        assertEquals(0, PlaybackPolicy.pageOffsetToMilliseconds(5, 10, 0));
    }
}
