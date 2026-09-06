package com.dwcode.okxbot.video.agent.step;

import com.dwcode.okxbot.video.enums.UnderstandingMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoStepAppliesTest {

    @Test
    void downloadAlwaysApplies() {
        DownloadStep step = new DownloadStep(null);
        for (UnderstandingMode mode : UnderstandingMode.values()) {
            assertTrue(step.applies(mode), mode.name());
        }
    }

    @Test
    void transcribeFollowsWhisper() {
        TranscribeStep step = new TranscribeStep(null, null, null);
        assertFalse(step.applies(UnderstandingMode.DOWNLOAD_ONLY));
        assertTrue(step.applies(UnderstandingMode.AUDIO_ONLY));
        assertTrue(step.applies(UnderstandingMode.HYBRID));
        assertFalse(step.applies(UnderstandingMode.OMNI_ONLY));
    }

    @Test
    void understandFollowsOmni() {
        UnderstandStep step = new UnderstandStep(null, null, null, null, null);
        assertFalse(step.applies(UnderstandingMode.DOWNLOAD_ONLY));
        assertFalse(step.applies(UnderstandingMode.AUDIO_ONLY));
        assertTrue(step.applies(UnderstandingMode.HYBRID));
        assertTrue(step.applies(UnderstandingMode.OMNI_ONLY));
    }

    @Test
    void summarizeFollowsLlm() {
        SummarizeStep step = new SummarizeStep(null, null, null, null);
        assertFalse(step.applies(UnderstandingMode.DOWNLOAD_ONLY));
        assertTrue(step.applies(UnderstandingMode.AUDIO_ONLY));
        assertTrue(step.applies(UnderstandingMode.HYBRID));
        assertTrue(step.applies(UnderstandingMode.OMNI_ONLY));
    }
}
