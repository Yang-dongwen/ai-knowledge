package com.dwcode.okxbot.video.util;

import com.dwcode.okxbot.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VideoUrlNormalizerSafetyTest {

    @Test
    void rejectsFileAndLoopback() {
        assertThrows(BusinessException.class, () -> VideoUrlNormalizer.assertSafeForDownload("file:///etc/passwd"));
        assertThrows(BusinessException.class, () -> VideoUrlNormalizer.assertSafeForDownload("http://localhost/x"));
        assertThrows(BusinessException.class, () -> VideoUrlNormalizer.assertSafeForDownload("http://127.0.0.1/x"));
        assertThrows(BusinessException.class, () -> VideoUrlNormalizer.assertSafeForDownload("http://[::1]/x"));
    }

    @Test
    void rejectsPrivateRanges() {
        assertThrows(BusinessException.class, () -> VideoUrlNormalizer.assertSafeForDownload("http://192.168.1.1/v"));
        assertThrows(BusinessException.class, () -> VideoUrlNormalizer.assertSafeForDownload("http://10.0.0.5/v"));
        assertThrows(BusinessException.class, () -> VideoUrlNormalizer.assertSafeForDownload("http://172.16.0.1/v"));
        assertThrows(BusinessException.class, () -> VideoUrlNormalizer.assertSafeForDownload("http://169.254.169.254/latest"));
        assertThrows(BusinessException.class, () -> VideoUrlNormalizer.assertSafeForDownload("http://100.64.0.1/v"));
    }

    @Test
    void rejectsUserInfo() {
        assertThrows(BusinessException.class,
                () -> VideoUrlNormalizer.assertSafeForDownload("https://user:pass@example.com/v"));
    }

    @Test
    void allowsPublicHttps() {
        assertDoesNotThrow(() -> VideoUrlNormalizer.assertSafeForDownload("https://www.douyin.com/video/123"));
    }

    @Test
    void normalizeDouyinModalId() {
        String n = VideoUrlNormalizer.normalize(
                "https://www.douyin.com/user/self?modal_id=7123456789012345678&showTab=like");
        assertEquals("https://www.douyin.com/video/7123456789012345678", n);
    }
}
