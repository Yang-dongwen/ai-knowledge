package com.dwcode.okxbot.kb.service;

import com.dwcode.okxbot.common.exception.BusinessException;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * 知识库附件文件名 / MIME / 类型规则。
 * <p>上传不限制扩展名与 MIME；活动内容（HTML/SVG 等）由 {@link KbMediaTypes} 在下载时强制 attachment。
 */
public final class KbFileRules {

    private KbFileRules() {
    }

    static String sanitizeOriginalName(String original) {
        if (!StringUtils.hasText(original)) {
            original = "file.bin";
        }
        original = original.replace("\\", "/");
        if (original.contains("/")) {
            original = original.substring(original.lastIndexOf('/') + 1);
        }
        if (original.length() > 200) {
            original = original.substring(original.length() - 200);
        }
        return original;
    }

    static String extensionOf(String name) {
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) {
            return "";
        }
        return name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    static String baseMime(String contentType) {
        if (contentType == null) {
            return "";
        }
        String ct = contentType.trim().toLowerCase(Locale.ROOT);
        int semi = ct.indexOf(';');
        if (semi >= 0) {
            ct = ct.substring(0, semi).trim();
        }
        return ct;
    }

    static String detectKind(String ext, String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.startsWith("image/") || Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp").contains(ext)) {
            return "image";
        }
        if (ct.startsWith("video/") || Set.of("mp4", "webm", "mov", "mkv").contains(ext)) {
            return "video";
        }
        if (ct.startsWith("audio/") || Set.of("mp3", "wav", "ogg", "m4a").contains(ext)) {
            return "audio";
        }
        if ("pdf".equals(ext) || ct.contains("pdf")) {
            return "pdf";
        }
        if (Set.of("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp").contains(ext)
                || ct.contains("officedocument") || ct.contains("msword") || ct.contains("ms-excel")
                || ct.contains("ms-powerpoint")) {
            return "office";
        }
        return "other";
    }

    static String sanitizeFileName(String name) {
        String n = name.replaceAll("[^A-Za-z0-9._@+-]", "_");
        if (n.isBlank()) {
            n = "file.bin";
        }
        if (n.length() > 120) {
            n = n.substring(n.length() - 120);
        }
        return n;
    }

    static String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return "application/octet-stream";
        }
        return contentType;
    }

    static String formatSizeLimit(long maxBytes) {
        long gb = 1024L * 1024 * 1024;
        if (maxBytes >= gb && maxBytes % gb == 0) {
            return (maxBytes / gb) + "GB";
        }
        return (maxBytes / 1024 / 1024) + "MB";
    }

    static void requireSize(long size, long maxBytes, String label) {
        if (size <= 0) {
            throw new BusinessException(400, "文件不能为空");
        }
        if (size > maxBytes) {
            throw new BusinessException(400, "文件过大，上限 " + formatSizeLimit(maxBytes));
        }
    }
}
