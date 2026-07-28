package com.dwcode.okxbot.article.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ArticleUrlNormalizerTest {

    @Test
    void stripQuotesAndAddHttps() {
        assertEquals("https://example.com/a",
                ArticleUrlNormalizer.normalize("\"example.com/a\""));
    }

    @Test
    void stripFragment() {
        String n = ArticleUrlNormalizer.normalize("https://example.com/a#section");
        assertEquals("https://example.com/a", n);
    }

    @Test
    void extractHost() {
        assertEquals("www.example.com",
                ArticleUrlNormalizer.extractHost("https://www.example.com/path"));
    }

    @Test
    void normalizeToutiaoArticleUrl() {
        assertEquals("https://www.toutiao.com/article/7664593441992524297/",
                ArticleUrlNormalizer.normalize(
                        "https://m.toutiao.com/i7664593441992524297/"));
    }

    @Test
    void extractToutiaoId() {
        assertEquals("7664593441992524297",
                ArticleUrlNormalizer.extractToutiaoId(
                        "https://www.toutiao.com/group/7664593441992524297/"));
    }

    @Test
    void emptyNull() {
        assertNull(ArticleUrlNormalizer.normalize("  "));
        assertNull(ArticleUrlNormalizer.normalize(null));
    }
}
