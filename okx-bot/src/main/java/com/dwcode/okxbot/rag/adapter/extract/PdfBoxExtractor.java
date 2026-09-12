package com.dwcode.okxbot.rag.adapter.extract;

import com.dwcode.okxbot.rag.port.TextExtractPort;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Locale;

@Component
public class PdfBoxExtractor implements TextExtractPort {

    @Override
    public boolean supports(String kind, String filename, String contentType) {
        if ("pdf".equalsIgnoreCase(kind)) {
            return true;
        }
        String n = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return n.endsWith(".pdf") || ct.contains("pdf");
    }

    @Override
    public String extract(InputStream in, String filename, long maxBytes) {
        try {
            byte[] buf = in.readNBytes((int) Math.min(Integer.MAX_VALUE, Math.max(1, maxBytes)));
            try (PDDocument doc = Loader.loadPDF(buf)) {
                PDFTextStripper stripper = new PDFTextStripper();
                String text = stripper.getText(doc);
                return text == null ? "" : text;
            }
        } catch (Exception e) {
            return "";
        }
    }
}
