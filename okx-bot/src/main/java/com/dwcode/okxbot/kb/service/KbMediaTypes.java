package com.dwcode.okxbot.kb.service;

import com.dwcode.okxbot.kb.entity.KbFileEntity;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * 知识库媒体 MIME 判定：优先魔数，再扩展名/库内 contentType。
 * <p>
 * 配合 {@code X-Content-Type-Options: nosniff} 时，扩展名与真实内容不一致
 * （例如 PNG 存成 .jpg）会导致浏览器直接拒显图片。
 */
public final class KbMediaTypes {

    private KbMediaTypes() {
    }

    /**
     * @param header 文件头若干字节，可为 null
     */
    public static MediaType resolve(KbFileEntity e, byte[] header) {
        MediaType sniffed = sniff(header);
        if (sniffed != null) {
            return sniffed;
        }
        return resolveByNameOrStored(e);
    }

    public static MediaType resolve(KbFileEntity e) {
        return resolve(e, null);
    }

    static MediaType sniff(byte[] header) {
        if (header == null || header.length < 3) {
            return null;
        }
        // PNG: 89 50 4E 47
        if (header.length >= 4
                && (header[0] & 0xFF) == 0x89
                && header[1] == 'P'
                && header[2] == 'N'
                && header[3] == 'G') {
            return MediaType.IMAGE_PNG;
        }
        // JPEG: FF D8 FF
        if ((header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8) {
            return MediaType.IMAGE_JPEG;
        }
        // GIF: GIF8
        if (header.length >= 4
                && header[0] == 'G'
                && header[1] == 'I'
                && header[2] == 'F'
                && header[3] == '8') {
            return MediaType.IMAGE_GIF;
        }
        // WEBP: RIFF....WEBP
        if (header.length >= 12
                && header[0] == 'R'
                && header[1] == 'I'
                && header[2] == 'F'
                && header[3] == 'F'
                && header[8] == 'W'
                && header[9] == 'E'
                && header[10] == 'B'
                && header[11] == 'P') {
            return MediaType.parseMediaType("image/webp");
        }
        // PDF: %PDF
        if (header.length >= 4
                && header[0] == '%'
                && header[1] == 'P'
                && header[2] == 'D'
                && header[3] == 'F') {
            return MediaType.APPLICATION_PDF;
        }
        return null;
    }

    static MediaType resolveByNameOrStored(KbFileEntity e) {
        String name = e == null || e.getOriginalName() == null
                ? ""
                : e.getOriginalName().toLowerCase(Locale.ROOT);

        // 库内 contentType 若是明确 image/*，优先于错误扩展名（扩展名仍可能骗过）
        if (e != null && StringUtils.hasText(e.getContentType())) {
            String ct = e.getContentType().trim().toLowerCase(Locale.ROOT);
            if (ct.startsWith("image/")
                    || ct.equals("application/pdf")
                    || ct.startsWith("video/")) {
                try {
                    return MediaType.parseMediaType(e.getContentType().trim());
                } catch (Exception ignored) {
                    // fall through to extension
                }
            }
        }

        if (name.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF;
        }
        if (name.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (name.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        if (name.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        if (name.endsWith(".mp4")) {
            return MediaType.parseMediaType("video/mp4");
        }
        if (name.endsWith(".webm")) {
            return MediaType.parseMediaType("video/webm");
        }
        if (name.endsWith(".docx")) {
            return MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        }
        if (name.endsWith(".xlsx")) {
            return MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        }
        if (name.endsWith(".pptx")) {
            return MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        }
        try {
            if (e != null && StringUtils.hasText(e.getContentType())) {
                return MediaType.parseMediaType(e.getContentType());
            }
        } catch (Exception ignored) {
            // fallthrough
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
