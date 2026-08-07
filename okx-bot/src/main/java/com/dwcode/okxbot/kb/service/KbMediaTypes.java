package com.dwcode.okxbot.kb.service;

import com.dwcode.okxbot.kb.entity.KbFileEntity;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * 知识库媒体 MIME 判定：优先魔数，再扩展名/库内 contentType。
 * <p>
 * 配合 {@code X-Content-Type-Options: nosniff} 时，扩展名与真实内容不一致
 * （例如 PNG 存成 .jpg）会导致浏览器直接拒显图片。
 * <p>
 * 活动内容（HTML/SVG/XML 等）永不作为其真实 MIME 响应，也不允许 inline，
 * 避免同 origin 下 SPA 被 XSS 并窃取 localStorage JWT。
 */
public final class KbMediaTypes {

    /** 库内/客户端 contentType 若为此类，不得原样回写到响应 */
    private static final Set<String> ACTIVE_CONTENT_TYPES = Set.of(
            "text/html",
            "image/svg+xml",
            "application/xhtml+xml",
            "text/xml",
            "application/xml",
            "application/javascript",
            "text/javascript",
            "text/css"
    );

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

    /**
     * 允许 {@code Content-Disposition: inline} 的类型白名单：
     * 栅格 image/*（排除 svg/xml）、application/pdf、video/*。
     */
    public static boolean isSafeInline(MediaType mt) {
        if (mt == null) {
            return false;
        }
        String type = mt.getType().toLowerCase(Locale.ROOT);
        String subtype = mt.getSubtype().toLowerCase(Locale.ROOT);
        if ("image".equals(type)) {
            return !subtype.contains("svg") && !subtype.contains("xml");
        }
        if ("application".equals(type) && "pdf".equals(subtype)) {
            return true;
        }
        return "video".equals(type);
    }

    /**
     * 响应用 Content-Type：白名单类型原样返回，其余强制 application/octet-stream。
     */
    public static MediaType responseMediaType(MediaType resolved) {
        if (resolved != null && isSafeInline(resolved)) {
            return resolved;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    static boolean isActiveContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return false;
        }
        String ct = contentType.trim().toLowerCase(Locale.ROOT);
        int semi = ct.indexOf(';');
        if (semi >= 0) {
            ct = ct.substring(0, semi).trim();
        }
        if (ACTIVE_CONTENT_TYPES.contains(ct)) {
            return true;
        }
        // 兜底：text/html; charset=... 已处理；svg 变体
        return ct.contains("svg+xml") || "text/html".equals(ct);
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

        // 库内 contentType 若是明确安全 image/* / pdf / video，优先于错误扩展名
        // 绝不信任 image/svg+xml 等活动类型
        if (e != null && StringUtils.hasText(e.getContentType())) {
            String ct = e.getContentType().trim().toLowerCase(Locale.ROOT);
            int semi = ct.indexOf(';');
            if (semi >= 0) {
                ct = ct.substring(0, semi).trim();
            }
            if (!isActiveContentType(ct)
                    && ((ct.startsWith("image/") && !ct.contains("svg") && !ct.contains("xml"))
                    || ct.equals("application/pdf")
                    || ct.startsWith("video/"))) {
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
        // 活动类型（html/svg/xml…）永不回写；其它未知类型也不原样信任
        if (e != null && StringUtils.hasText(e.getContentType())
                && !isActiveContentType(e.getContentType())) {
            String ct = e.getContentType().trim().toLowerCase(Locale.ROOT);
            // 仅放行已识别为 office 等非活动、非预览类的常见类型
            if (ct.contains("officedocument") || ct.contains("msword")
                    || ct.contains("ms-excel") || ct.contains("ms-powerpoint")
                    || ct.startsWith("audio/")
                    || ct.equals("application/octet-stream")
                    || ct.equals("text/plain")) {
                try {
                    return MediaType.parseMediaType(e.getContentType().trim());
                } catch (Exception ignored) {
                    // fallthrough
                }
            }
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
