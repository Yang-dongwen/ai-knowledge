package com.dwcode.okxbot.article.util;

import com.dwcode.okxbot.article.enums.ArticleSupportLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticlePlatformDetectorTest {

    private ArticlePlatformDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ArticlePlatformDetector();
    }

    @Test
    void xiaohongshuPasteOnly() {
        ArticlePlatformInfo info = detector.detect("https://www.xiaohongshu.com/explore/abc");
        assertEquals("xiaohongshu", info.getPlatform());
        assertEquals(ArticleSupportLevel.PASTE_ONLY, info.getSupportLevel());
        assertTrue(detector.shouldSkipFetch(info.getSupportLevel()));
    }

    @Test
    void xhslinkPasteOnly() {
        ArticlePlatformInfo info = detector.detect("https://xhslink.com/m/xxxxx");
        assertEquals("xiaohongshu", info.getPlatform());
        assertEquals(ArticleSupportLevel.PASTE_ONLY, info.getSupportLevel());
    }

    @Test
    void toutiaoPartialAutoExtract() {
        ArticlePlatformInfo info = detector.detect("https://www.toutiao.com/article/123");
        assertEquals("toutiao", info.getPlatform());
        assertEquals(ArticleSupportLevel.PARTIAL, info.getSupportLevel());
        assertFalse(detector.shouldSkipFetch(info.getSupportLevel()));
    }

    @Test
    void xPasteOnly() {
        ArticlePlatformInfo info = detector.detect("https://x.com/user/status/1");
        assertEquals("x", info.getPlatform());
        assertEquals(ArticleSupportLevel.PASTE_ONLY, info.getSupportLevel());
    }

    @Test
    void weixinPartial() {
        ArticlePlatformInfo info = detector.detect("https://mp.weixin.qq.com/s/abc");
        assertEquals("weixin", info.getPlatform());
        assertEquals(ArticleSupportLevel.PARTIAL, info.getSupportLevel());
    }

    @Test
    void zhihuPartial() {
        ArticlePlatformInfo info = detector.detect("https://www.zhihu.com/question/1/answer/2");
        assertEquals("zhihu", info.getPlatform());
        assertEquals(ArticleSupportLevel.PARTIAL, info.getSupportLevel());
    }

    @Test
    void bilibiliColumnPartial() {
        ArticlePlatformInfo info = detector.detect("https://www.bilibili.com/read/cv123456");
        assertEquals("bilibili_column", info.getPlatform());
        assertEquals(ArticleSupportLevel.PARTIAL, info.getSupportLevel());
    }

    @Test
    void bilibiliVideoUnsupported() {
        ArticlePlatformInfo info = detector.detect("https://www.bilibili.com/video/BV1xx");
        assertEquals("bilibili", info.getPlatform());
        assertEquals(ArticleSupportLevel.UNSUPPORTED, info.getSupportLevel());
    }

    @Test
    void genericFull() {
        ArticlePlatformInfo info = detector.detect("https://www.example.com/news/123");
        assertEquals("generic", info.getPlatform());
        assertEquals(ArticleSupportLevel.FULL, info.getSupportLevel());
    }
}
