package com.dwcode.okxbot.video.port;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoUnderstandingProtocolTest {

    @Test
    void normalizeBlankIsAuto() {
        assertEquals(VideoUnderstandingProtocol.AUTO, VideoUnderstandingProtocol.normalize(null));
        assertEquals(VideoUnderstandingProtocol.AUTO, VideoUnderstandingProtocol.normalize("  "));
        assertEquals(VideoUnderstandingProtocol.FRAME, VideoUnderstandingProtocol.normalize("Frame-VLM"));
    }

    @Test
    void mockFromConfigOrProtocol() {
        assertTrue(VideoUnderstandingProtocol.isMock("auto", true));
        assertTrue(VideoUnderstandingProtocol.isMock("mock", false));
        assertFalse(VideoUnderstandingProtocol.isMock("auto", false));
    }

    @Test
    void omniVsFrame() {
        assertTrue(VideoUnderstandingProtocol.useOmni("auto"));
        assertTrue(VideoUnderstandingProtocol.useOmni("nvidia-omni-chat"));
        assertFalse(VideoUnderstandingProtocol.useOmni("frame-vlm"));
        assertTrue(VideoUnderstandingProtocol.forceFrame("frame-vlm"));
        assertFalse(VideoUnderstandingProtocol.forceFrame("auto"));
    }
}
