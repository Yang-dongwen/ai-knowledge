package com.dwcode.okxbot.rag.adapter.extract;

import com.dwcode.okxbot.rag.port.TextExtractPort;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Locale;
import java.util.Set;

@Component
public class OfficeExtractor implements TextExtractPort {

    private static final Set<String> WORD = Set.of("doc", "docx");
    private static final Set<String> SHEET = Set.of("xls", "xlsx");

    @Override
    public boolean supports(String kind, String filename, String contentType) {
        if ("office".equalsIgnoreCase(kind)) {
            return true;
        }
        String ext = ext(filename);
        return WORD.contains(ext) || SHEET.contains(ext);
    }

    @Override
    public String extract(InputStream in, String filename, long maxBytes) {
        String ext = ext(filename);
        try {
            if (SHEET.contains(ext)) {
                return extractSheet(in);
            }
            if ("docx".equals(ext) || "office".equalsIgnoreCase(guessKind(filename))) {
                try (XWPFDocument doc = new XWPFDocument(in);
                     XWPFWordExtractor ex = new XWPFWordExtractor(doc)) {
                    String t = ex.getText();
                    return t == null ? "" : t;
                }
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String extractSheet(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(in)) {
            int sheets = Math.min(wb.getNumberOfSheets(), 8);
            for (int i = 0; i < sheets; i++) {
                Sheet sheet = wb.getSheetAt(i);
                if (sheet == null) {
                    continue;
                }
                int rows = 0;
                for (Row row : sheet) {
                    if (rows++ > 400) {
                        break;
                    }
                    if (row == null) {
                        continue;
                    }
                    int cells = 0;
                    for (Cell cell : row) {
                        if (cells++ > 40) {
                            break;
                        }
                        String v = fmt.formatCellValue(cell);
                        if (v != null && !v.isBlank()) {
                            if (sb.length() > 0) {
                                sb.append(' ');
                            }
                            sb.append(v.trim());
                        }
                    }
                    sb.append('\n');
                    if (sb.length() > 200_000) {
                        return sb.toString();
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String ext(String filename) {
        if (filename == null) {
            return "";
        }
        String n = filename.toLowerCase(Locale.ROOT);
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1);
    }

    private static String guessKind(String filename) {
        return "office";
    }
}
