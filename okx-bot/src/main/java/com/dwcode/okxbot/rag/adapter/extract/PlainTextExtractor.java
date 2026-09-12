package com.dwcode.okxbot.rag.adapter.extract;

import com.dwcode.okxbot.rag.port.TextExtractPort;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

@Component
public class PlainTextExtractor implements TextExtractPort {

    private static final Set<String> EXT = Set.of("txt", "md", "markdown", "csv", "json", "xml", "html", "htm", "log");

    @Override
    public boolean supports(String kind, String filename, String contentType) {
        String n = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        int i = n.lastIndexOf('.');
        String ext = i < 0 ? "" : n.substring(i + 1);
        if (EXT.contains(ext)) {
            return true;
        }
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return ct.startsWith("text/");
    }

    @Override
    public String extract(InputStream in, String filename, long maxBytes) {
        try {
            byte[] buf = in.readNBytes((int) Math.min(Integer.MAX_VALUE, Math.max(1, maxBytes)));
            return new String(buf, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
