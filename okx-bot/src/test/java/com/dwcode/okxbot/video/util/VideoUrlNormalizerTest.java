package com.dwcode.okxbot.video.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VideoUrlNormalizerTest {

    @Test
    void normalizeDouyinModalIdFromUserSelf() {
        String raw = "https://www.douyin.com/user/self?modal_id=7664420714195553659&showTab=like";
        assertEquals(
                "https://www.douyin.com/video/7664420714195553659",
                VideoUrlNormalizer.normalize(raw));
    }

    @Test
    void normalizeDouyinModalIdFromDiscover() {
        String raw = "https://www.douyin.com/discover?modal_id=1234567890";
        assertEquals("https://www.douyin.com/video/1234567890", VideoUrlNormalizer.normalize(raw));
    }

    @Test
    void normalizeAlreadyStandardVideoUrl() {
        String raw = "https://www.douyin.com/video/7664420714195553659?previous_page=web_code_link";
        assertEquals(
                "https://www.douyin.com/video/7664420714195553659",
                VideoUrlNormalizer.normalize(raw));
    }

    @Test
    void normalizeIesdouyinShare() {
        String raw = "https://www.iesdouyin.com/share/video/7664420714195553659/?region=CN";
        assertEquals(
                "https://www.douyin.com/video/7664420714195553659",
                VideoUrlNormalizer.normalize(raw));
    }

    @Test
    void normalizeNotePathToVideo() {
        String raw = "https://www.douyin.com/note/7664420714195553659";
        assertEquals(
                "https://www.douyin.com/video/7664420714195553659",
                VideoUrlNormalizer.normalize(raw));
    }

    @Test
    void leaveBilibiliUnchanged() {
        String raw = "https://www.bilibili.com/video/BV1xx411c7mD";
        assertEquals(raw, VideoUrlNormalizer.normalize(raw));
    }

    @Test
    void trimAndStripQuotes() {
        String raw = "\"https://www.douyin.com/user/self?modal_id=99\"";
        assertEquals("https://www.douyin.com/video/99", VideoUrlNormalizer.normalize(raw));
    }

    @Test
    void nullAndBlank() {
        assertNull(VideoUrlNormalizer.normalize(null));
        assertEquals("", VideoUrlNormalizer.normalize("   "));
    }
}
