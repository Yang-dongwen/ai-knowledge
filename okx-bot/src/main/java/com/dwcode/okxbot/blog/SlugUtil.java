package com.dwcode.okxbot.blog;

import java.util.Locale;

/**
 * Halo slug：ASCII 短横线；中文标题退化为 fallback。
 */
public final class SlugUtil {

    private SlugUtil() {
    }

    public static String fromTitle(String title, String fallback) {
        String fb = sanitizeFallback(fallback);
        if (title == null || title.isBlank()) {
            return fb;
        }
        StringBuilder sb = new StringBuilder();
        boolean dash = false;
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                dash = false;
            } else if (c >= 'A' && c <= 'Z') {
                sb.append(Character.toLowerCase(c));
                dash = false;
            } else if (c == ' ' || c == '_' || c == '-' || c == '/' || c == '.') {
                if (!dash && sb.length() > 0) {
                    sb.append('-');
                    dash = true;
                }
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.setLength(sb.length() - 1);
        }
        if (sb.length() < 2) {
            return fb;
        }
        if (sb.length() > 80) {
            sb.setLength(80);
            while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
                sb.setLength(sb.length() - 1);
            }
        }
        return sb.toString();
    }

    private static String sanitizeFallback(String fallback) {
        if (fallback == null || fallback.isBlank()) {
            return "post";
        }
        return fallback.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
    }
}
