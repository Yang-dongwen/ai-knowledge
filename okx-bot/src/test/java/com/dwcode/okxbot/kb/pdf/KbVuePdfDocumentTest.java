package com.dwcode.okxbot.kb.pdf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KbVuePdfDocumentTest {

    @Test
    void wrapIncludesTitleBodyAndFonts() {
        String html = KbVuePdfDocument.wrap("杨栋文 / 笔记", "<h1>Hi</h1><p>body</p>", "h1{color:red}");
        assertTrue(html.contains("<title>杨栋文 / 笔记</title>"));
        assertTrue(html.contains("<article id=\"write\"><h1>Hi</h1><p>body</p></article>"));
        assertTrue(html.contains("source-sans-pro-400.woff2"));
        assertTrue(html.contains("Noto Sans SC"));
        assertTrue(html.contains("h1{color:red}"));
    }

    @Test
    void wrapEscapesTitleAndStripsScript() {
        String html = KbVuePdfDocument.wrap("<x>", "<p>ok</p><script>alert(1)</script>", "");
        assertTrue(html.contains("<title>&lt;x&gt;</title>"));
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("<p>ok</p>"));
    }
}
