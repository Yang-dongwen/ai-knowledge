package com.dwcode.okxbot.rag.adapter.extract;

import com.dwcode.okxbot.rag.port.TextExtractPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

@Component
@RequiredArgsConstructor
public class TextExtractRouter {

    private final List<TextExtractPort> extractors;

    public String extract(String kind, String filename, String contentType, InputStream in, long maxBytes) {
        if (in == null) {
            return "";
        }
        for (TextExtractPort p : extractors) {
            if (p.supports(kind, filename, contentType)) {
                String t = p.extract(in, filename, maxBytes);
                return t == null ? "" : t;
            }
        }
        return "";
    }
}
