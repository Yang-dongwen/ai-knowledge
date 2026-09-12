package com.dwcode.okxbot.rag.chunk;

import com.dwcode.okxbot.rag.port.TextChunk;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 按字符切块，优先在段落/换行处断开。
 */
@Component
public class CharOverlapChunker {

    public List<TextChunk> chunk(String text, int size, int overlap) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        String src = text.replace("\r\n", "\n").trim();
        if (src.isEmpty()) {
            return List.of();
        }
        int chunkSize = Math.max(200, size);
        int ov = Math.max(0, Math.min(overlap, chunkSize / 2));
        List<TextChunk> out = new ArrayList<>();
        int i = 0;
        int idx = 0;
        while (i < src.length()) {
            int end = Math.min(src.length(), i + chunkSize);
            if (end < src.length()) {
                int breakAt = lastBreak(src, i, end);
                if (breakAt > i + chunkSize / 3) {
                    end = breakAt;
                }
            }
            String piece = src.substring(i, end).trim();
            if (!piece.isEmpty()) {
                out.add(new TextChunk(idx++, piece));
            }
            if (end >= src.length()) {
                break;
            }
            i = Math.max(i + 1, end - ov);
        }
        return out;
    }

    private static int lastBreak(String src, int start, int end) {
        int nl = src.lastIndexOf('\n', end - 1);
        if (nl > start) {
            return nl + 1;
        }
        int sp = src.lastIndexOf(' ', end - 1);
        if (sp > start) {
            return sp + 1;
        }
        return end;
    }
}
