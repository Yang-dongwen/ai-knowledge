package com.dwcode.okxbot.article.util;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文章链接规范化：去包裹符、补 scheme、头条 ID 归一化。
 */
public final class ArticleUrlNormalizer {

    private static final Pattern TOUTIAO_PATH_ID = Pattern.compile(
            "(?i)/(?:article|group|item|a|i|w)/?(\\d{10,})");
    private static final Pattern TOUTIAO_ARTICLE_ID = Pattern.compile(
            "(?i)article/(\\d{10,})");
    private static final Pattern TOUTIAO_QUERY_ID = Pattern.compile(
            "(?i)(?:group_id|item_id|article_id)=(\\d{10,})");

    private ArticleUrlNormalizer() {
    }

    /**
     * 规范化；无法处理时返回 trim 后的输入；空输入返回 null。
     */
    public static String normalize(String rawUrl) {
        if (rawUrl == null) {
            return null;
        }
        String url = rawUrl.trim();
        if (url.isEmpty()) {
            return null;
        }
        if ((url.startsWith("\"") && url.endsWith("\""))
                || (url.startsWith("'") && url.endsWith("'"))
                || (url.startsWith("<") && url.endsWith(">"))) {
            url = url.substring(1, url.length() - 1).trim();
        }
        url = url.replace('\u00A0', ' ').trim();

        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")
                && !lower.startsWith("file:") && !lower.contains("://")) {
            if (url.contains(".") || url.startsWith("localhost") || url.startsWith("[")) {
                url = "https://" + url;
            }
        }

        if (url.endsWith("…") || url.endsWith("...")) {
            url = url.replaceAll("\\u2026+$", "").replaceAll("\\.\\.\\.$", "");
        }

        try {
            URI uri = new URI(url);
            if (uri.getFragment() != null) {
                uri = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(),
                        uri.getQuery(), null);
                url = uri.toString();
            }
        } catch (Exception ignored) {
            // 保持 trim 结果
        }

        String toutiaoId = extractToutiaoId(url);
        if (toutiaoId != null && (url.toLowerCase(Locale.ROOT).contains("toutiao.com")
                || url.toLowerCase(Locale.ROOT).contains("ixigua.com"))) {
            return "https://www.toutiao.com/article/" + toutiaoId + "/";
        }

        return url;
    }

    /**
     * 从头条 / 西瓜链接解析文章数字 ID；非相关链接返回 null。
     */
    public static String extractToutiaoId(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.contains("toutiao.com") && !lower.contains("ixigua.com")
                && !url.matches("\\d{10,}")) {
            return null;
        }
        Matcher m = TOUTIAO_PATH_ID.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        m = TOUTIAO_ARTICLE_ID.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        m = TOUTIAO_QUERY_ID.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        if (url.matches("\\d{10,}")) {
            return url;
        }
        return null;
    }

    /**
     * 提取 host（小写）；失败返回 null。
     */
    public static String extractHost(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            return host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }
}
