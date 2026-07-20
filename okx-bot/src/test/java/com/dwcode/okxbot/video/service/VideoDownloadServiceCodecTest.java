package com.dwcode.okxbot.video.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoDownloadServiceCodecTest {

    @Test
    void browserFriendlyCodecs() {
        assertTrue(VideoDownloadService.isBrowserFriendlyVideoCodec("h264"));
        assertTrue(VideoDownloadService.isBrowserFriendlyVideoCodec("avc1"));
        assertTrue(VideoDownloadService.isBrowserFriendlyVideoCodec("vp9"));
        assertTrue(VideoDownloadService.isBrowserFriendlyVideoCodec(null));
    }

    @Test
    void nonBrowserFriendlyCodecs() {
        assertFalse(VideoDownloadService.isBrowserFriendlyVideoCodec("hevc"));
        assertFalse(VideoDownloadService.isBrowserFriendlyVideoCodec("h265"));
        assertFalse(VideoDownloadService.isBrowserFriendlyVideoCodec("av1"));
        assertFalse(VideoDownloadService.isBrowserFriendlyVideoCodec("mpeg4"));
    }
}
