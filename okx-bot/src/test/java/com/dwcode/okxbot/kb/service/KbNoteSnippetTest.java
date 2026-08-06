package com.dwcode.okxbot.kb.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KbNoteSnippetTest {

    @Test
    void buildSnippet_stripsMarkdownAndTruncates() {
        String md = "# 标题\n\n这是 **加粗** 与 `code` 的内容。";
        String snippet = KbNoteService.buildSnippet(md);
        assertTrue(snippet.contains("标题") || snippet.contains("加粗") || snippet.contains("内容"));
        assertTrue(!snippet.contains("**"));
    }

    @Test
    void buildSnippet_empty() {
        assertEquals("", KbNoteService.buildSnippet(null));
        assertEquals("", KbNoteService.buildSnippet("   "));
    }

    @Test
    void buildSnippet_longText() {
        String longText = "字".repeat(300);
        String snippet = KbNoteService.buildSnippet(longText);
        assertTrue(snippet.endsWith("…"));
        assertTrue(snippet.length() <= 161);
    }

    @Test
    void extractFileIdsFromContent() {
        String html = "<p><img src=\"/api/v1/kb/files/1234567890123456789/content\"/>"
                + "<video src=\"/api/v1/kb/files/99/content?access_token=x\"></video></p>";
        Set<Long> ids = KbNoteService.extractFileIdsFromContent(html);
        assertEquals(2, ids.size());
        assertTrue(ids.contains(1234567890123456789L));
        assertTrue(ids.contains(99L));
    }

    @Test
    void toPlainText_stripsHtml() {
        String plain = KbNoteService.toPlainText("<p>你好<strong>世界</strong></p>", "html");
        assertTrue(plain.contains("你好"));
        assertTrue(plain.contains("世界"));
        assertTrue(!plain.contains("<"));
    }

    @Test
    void buildMatchSnippet_fromBody() {
        String body = "前文若干字 " + "x".repeat(20) + " 关键词命中 " + "y".repeat(20) + " 后文";
        String match = KbNoteService.buildMatchSnippet("无关标题", body, null, "关键词", 10);
        assertTrue(match != null && match.contains("关键词"));
    }
}
