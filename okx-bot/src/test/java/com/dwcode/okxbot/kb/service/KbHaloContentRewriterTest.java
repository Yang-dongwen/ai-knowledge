package com.dwcode.okxbot.kb.service;

import com.dwcode.okxbot.kb.entity.KbFileEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KbHaloContentRewriterTest {

    @Test
    void collectsHtmlAndMarkdownRefs() {
        String html = "<img src=\"/api/v1/kb/files/11/content\"><a href=\"https://x/api/v1/kb/files/22/content?access_token=a\">";
        String md = "![a](/api/v1/kb/files/11/content) [pdf](/api/v1/kb/files/33/content)";
        assertEquals(Set.of(11L, 22L), KbHaloContentRewriter.collectFileIds(html));
        assertEquals(Set.of(11L, 33L), KbHaloContentRewriter.collectFileIds(md));
    }

    @Test
    void replacesWithPublicUrls() {
        String md = "![a](/api/v1/kb/files/11/content?access_token=x)";
        String out = KbHaloContentRewriter.replaceFileUrls(md, Map.of(11L, "https://blog.example.com/upload/a.png"));
        assertEquals("![a](https://blog.example.com/upload/a.png)", out);
    }

    @Test
    void firstImageFromMarkdownThenHtml() {
        assertEquals("https://blog.example.com/a.png",
                KbHaloContentRewriter.firstImageUrl("hi ![x](https://blog.example.com/a.png)"));
        assertEquals("https://cdn.example.com/b.jpg",
                KbHaloContentRewriter.firstImageUrl("<p><img src=\"https://cdn.example.com/b.jpg\"></p>"));
        assertEquals(null, KbHaloContentRewriter.firstImageUrl("![x](/api/v1/kb/files/1/content)"));
    }

    @Test
    void firstBoundImageSkipsNonImage() {
        KbFileEntity pdf = new KbFileEntity();
        pdf.setId(1L);
        pdf.setKind("pdf");
        KbFileEntity img = new KbFileEntity();
        img.setId(2L);
        img.setKind("image");
        String url = KbHaloContentRewriter.firstBoundImagePermalink(
                List.of(pdf, img),
                Map.of(1L, "https://blog.example.com/a.pdf", 2L, "https://blog.example.com/a.png"));
        assertEquals("https://blog.example.com/a.png", url);
    }

    @Test
    void appendsUnreferencedAttachments() {
        KbFileEntity pdf = new KbFileEntity();
        pdf.setId(9L);
        pdf.setOriginalName("spec.pdf");
        String html = "<p>hi</p>";
        String out = KbHaloContentRewriter.appendExtraAttachments(
                html, "HTML", List.of(pdf), f -> "https://blog.example.com/upload/spec.pdf");
        assertTrue(out.contains("附件"));
        assertTrue(out.contains("spec.pdf"));
        assertTrue(out.contains("https://blog.example.com/upload/spec.pdf"));
    }
}
