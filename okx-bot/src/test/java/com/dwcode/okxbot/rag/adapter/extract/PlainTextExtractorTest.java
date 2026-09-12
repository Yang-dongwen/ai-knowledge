package com.dwcode.okxbot.rag.adapter.extract;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlainTextExtractorTest {

    private final PlainTextExtractor extractor = new PlainTextExtractor();

    @Test
    void supportsTextExtensions() {
        assertTrue(extractor.supports("other", "note.md", "text/markdown"));
        assertTrue(extractor.supports("other", "a.txt", "text/plain"));
        assertFalse(extractor.supports("pdf", "a.pdf", "application/pdf"));
    }

    @Test
    void readsUtf8() {
        byte[] bytes = "你好知识库".getBytes(StandardCharsets.UTF_8);
        String text = extractor.extract(new ByteArrayInputStream(bytes), "a.txt", 1024);
        assertEquals("你好知识库", text);
    }
}
