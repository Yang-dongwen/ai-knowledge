package com.dwcode.okxbot.blog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownToHtmlTest {

    @Test
    void rendersHeadingAndParagraph() {
        String html = MarkdownToHtml.render("# Hello\n\nworld");
        assertTrue(html.contains("<h1>"));
        assertTrue(html.contains("Hello"));
        assertTrue(html.contains("<p>"));
        assertTrue(html.contains("world"));
    }

    @Test
    void emptySafe() {
        assertTrue(MarkdownToHtml.render(null).isEmpty());
        assertTrue(MarkdownToHtml.render("  ").isEmpty());
    }
}
