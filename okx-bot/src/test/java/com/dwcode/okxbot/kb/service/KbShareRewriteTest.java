package com.dwcode.okxbot.kb.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KbShareRewriteTest {

    @Test
    void rewriteContentForPublic_htmlAndMarkdown() {
        String token = "AbC123_-xy";
        String src = """
                <p><img src="/api/v1/kb/files/111/content"/></p>
                ![x](/api/v1/kb/files/222/content?access_token=old)
                <img src="http://localhost:3000/api/v1/kb/files/333/content?access_token=x">
                """;
        String out = KbShareService.rewriteContentForPublic(src, token);
        assertTrue(out.contains("/api/v1/kb/public/s/AbC123_-xy/files/111/content"));
        assertTrue(out.contains("/api/v1/kb/public/s/AbC123_-xy/files/222/content"));
        assertTrue(out.contains("/api/v1/kb/public/s/AbC123_-xy/files/333/content"));
        assertTrue(!out.contains("access_token=old"));
        assertTrue(!out.contains("/api/v1/kb/files/111/content\""));
        assertTrue(!out.contains("localhost:3000/api/v1/kb/files"));
    }

    @Test
    void rewrite_empty() {
        assertEquals(null, KbShareService.rewriteContentForPublic(null, "t"));
        assertEquals("", KbShareService.rewriteContentForPublic("", "t"));
    }
}
