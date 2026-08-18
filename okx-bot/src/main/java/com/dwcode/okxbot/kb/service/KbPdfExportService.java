package com.dwcode.okxbot.kb.service;

import com.dwcode.okxbot.blog.MarkdownToHtml;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.kb.config.KbProperties;
import com.dwcode.okxbot.kb.dto.KbPdfExportRequest;
import com.dwcode.okxbot.kb.dto.NoteResponse;
import com.dwcode.okxbot.kb.pdf.KbChromePdfRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class KbPdfExportService {

    private final KbNoteService noteService;
    private final KbChromePdfRenderer renderer;
    private final KbProperties properties;

    public record PdfFile(byte[] bytes, String filename) {
    }

    public PdfFile export(Long noteId, KbPdfExportRequest request) {
        NoteResponse meta = noteService.get(noteId);
        String title = firstTitle(request != null ? request.getTitle() : null, meta.getTitle());
        String html = request != null ? request.getHtml() : null;
        if (StringUtils.hasText(html)) {
            int max = properties.getPdf().getMaxHtmlChars();
            if (html.length() > max) {
                throw new BusinessException(413, "文档过大，无法导出 PDF");
            }
        } else {
            html = renderSaved(meta);
        }
        if (!StringUtils.hasText(html.replaceAll("<[^>]+>", "").trim())) {
            throw new BusinessException(400, "文档为空，无法导出");
        }
        byte[] pdf = renderer.render(title, html);
        return new PdfFile(pdf, safeFilename(title) + ".pdf");
    }

    private static String renderSaved(NoteResponse meta) {
        String body = meta.getContent() == null ? "" : meta.getContent();
        if ("markdown".equalsIgnoreCase(meta.getContentFormat())) {
            return MarkdownToHtml.render(body);
        }
        return body;
    }

    private static String firstTitle(String a, String b) {
        if (StringUtils.hasText(a)) {
            return a.trim();
        }
        if (StringUtils.hasText(b)) {
            return b.trim();
        }
        return "未命名笔记";
    }

    static String safeFilename(String title) {
        String t = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return t.isEmpty() ? "未命名笔记" : t;
    }
}
