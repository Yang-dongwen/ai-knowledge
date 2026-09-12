package com.dwcode.okxbot.rag.chunk;

import com.dwcode.okxbot.rag.port.TextChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharOverlapChunkerTest {

    private final CharOverlapChunker chunker = new CharOverlapChunker();

    @Test
    void empty() {
        assertTrue(chunker.chunk("", 800, 120).isEmpty());
        assertTrue(chunker.chunk("   ", 800, 120).isEmpty());
    }

    @Test
    void splitsLongText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("段落").append(i).append(" 内容足够长以便切开。\n");
        }
        List<TextChunk> chunks = chunker.chunk(sb.toString(), 200, 40);
        assertTrue(chunks.size() >= 2);
        assertEquals(0, chunks.get(0).getIndex());
        assertTrue(chunks.get(0).getText().length() <= 220);
    }
}
