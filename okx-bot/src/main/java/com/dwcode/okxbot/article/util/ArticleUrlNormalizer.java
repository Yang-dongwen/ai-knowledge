package com.dwcode.okxbot.article.util;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 文章链接规范化：去包裹符、补 scheme、常见跟踪参数清理（不改变语义路径）。
 */
public final class ArticleUrlNormalizer {

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
        // 聊天软件包裹
        if ((url.startsWith("\"") && url.endsWith("\""))
                || (url.startsWith("'") && url.endsWith("'"))
                || (url.startsWith("<") && url.endsWith(">"))) {
            url = url.substring(1, url.length() - 1).trim();
        }
        // 全角空格
        url = url.replace('\u00A0', ' ').trim();

        // 无 scheme：默认 https
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")
                && !lower.startsWith("file:") && !lower.contains("://")) {
            // 排除明显非 URL
            if (url.contains(".") || url.startsWith("localhost") || url.startsWith("[")) {
                url = "https://" + url;
            }
        }

        // 小红书短链保持原样（需跳转解析，PR-4+）
        // 微信 / 知乎常见末尾垃圾
        if (url.endsWith("…") || url.endsWith("...")) {
            // 不截断有效 path，仅去掉字面省略号（不完整链接交校验失败）
            url = url.replaceAll("\\u2026+$", "").replaceAll("\\.\\.\\.$", "");
        }

        try {
            URI uri = new URI(url);
            // 去掉 fragment（# 后通常无服务端语义）
            if (uri.getFragment() != null) {
                uri = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(),
                        uri.getQuery(), null);
                url = uri.toString();
            }
        } catch (Exception ignored) {
            // 保持 trim 结果
        }

        // 头条：统一为可解析的 article URL（便于 ID 提取与抓取）
        String toutiaoId = extractToutiaoId(url);
        if (toutiaoId != null) {
            return "https://www.toutiao.com/article/" + toutiaoId + "/";
        }

        return url;
    }

    /**
     * 从头条链接解析文章数字 ID；非头条返回 null。
     */
    public static String extractToutiaoId(String url) {
        if (url == null) {
            return null;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.contains("toutiao.com") && !lower.matches("\\d{10,}")) {
            return null;
        }
        // 延迟加载式：与 ToutiaoFetchAdapter 同一规则（避免循环依赖，复制精简正则）
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)/(?:article|group|item|a|i|w)/?(\\d{10,})").matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        m = java.util.regex.Pattern.compile("(?i)article/(\\d{10,})").matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        m = java.util.regex.Pattern.compile(
                "(?i)(?:group_id|item_id|article_id)=(\\d{10,})").matcher(url);
        if (m.find()) {
            return m.group(1);
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

    /**
     * 可选：去掉常见追踪参数（不改变 path）。失败则返回原 URL。
     */
    public static String stripTrackingParams(String url) {
        if (url == null || !url.contains("?")) {
            return url;
        }
        try {
            URI uri = new URI(url);
            String query = uri.getRawQuery();
            if (query == null || query.isBlank()) {
                return url;
            }
            String[] parts = query.split("&");
            StringBuilder kept = new StringBuilder();
            for (String p : parts) {
                if (p.isBlank()) {
                    continue;
                }
                String key = p;
                int eq = p.indexOf('=');
                if (eq > 0) {
                    key = p.substring(0, eq);
                }
                String keyDec = URLDecoder.decode(key, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if (keyDec.startsWith("utm_") || keyDec.equals("spm") || keyDec.equals("from")
                        || keyDec.equals("share_token") || keyDec.equals("scene")) {
                    continue;
                }
                if (kept.length() > 0) {
                    kept.append('&');
                }
                kept.append(p);
            }
            String newQuery = kept.length() == 0 ? null : kept.toString();
            URI cleaned = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(),
                    newQuery, uri.getFragment());
            return cleaned.toString();
        } catch (Exception e) {
            return url;
        }
    }
}
