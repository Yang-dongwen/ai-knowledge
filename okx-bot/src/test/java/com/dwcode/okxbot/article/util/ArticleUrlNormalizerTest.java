package com.dwcode.okxbot.article.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void stripUtm() {
        String n = ArticleUrlNormalizer.stripTrackingParams(
                "https://example.com/a?utm_source=x&id=1");
        assertTrue(n.contains("id=1"));
        assertTrue(!n.contains("utm_source"));
    }

    @Test
    void emptyNull() {
        assertNull(ArticleUrlNormalizer.normalize("  "));
        assertNull(ArticleUrlNormalizer.normalize(null));
    }
}
