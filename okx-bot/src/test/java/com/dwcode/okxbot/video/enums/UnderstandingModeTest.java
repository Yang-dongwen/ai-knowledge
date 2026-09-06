package com.dwcode.okxbot.video.enums;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnderstandingModeTest {

    @Test
    void downloadOnlyHasNoFollowUpSteps() {
        assertEquals(List.of(), UnderstandingMode.DOWNLOAD_ONLY.stepsAfterDownload());
        assertFalse(UnderstandingMode.DOWNLOAD_ONLY.needsLlm());
    }

    @Test
    void audioOnlyTranscribeThenSummarize() {
        assertEquals(List.of("transcribe", "summarize"),
                UnderstandingMode.AUDIO_ONLY.stepsAfterDownload());
        assertTrue(UnderstandingMode.AUDIO_ONLY.needsWhisper());
        assertFalse(UnderstandingMode.AUDIO_ONLY.needsOmni());
    }

    @Test
    void hybridIncludesUnderstand() {
        assertEquals(List.of("transcribe", "understand", "summarize"),
                UnderstandingMode.HYBRID.stepsAfterDownload());
        assertTrue(UnderstandingMode.HYBRID.needsWhisper());
        assertTrue(UnderstandingMode.HYBRID.needsOmni());
    }

    @Test
    void omniOnlySkipsWhisper() {
        assertEquals(List.of("understand", "summarize"),
                UnderstandingMode.OMNI_ONLY.stepsAfterDownload());
        assertFalse(UnderstandingMode.OMNI_ONLY.needsWhisper());
        assertTrue(UnderstandingMode.OMNI_ONLY.needsOmni());
    }
}
