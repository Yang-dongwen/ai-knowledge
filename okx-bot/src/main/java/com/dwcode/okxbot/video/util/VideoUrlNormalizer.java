package com.dwcode.okxbot.video.util;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 各平台粘贴链接规范化，便于 yt-dlp 识别。
 *
 * <p>典型问题：抖音从「我的喜欢 / 个人页」复制的链接形如
 * {@code https://www.douyin.com/user/self?modal_id=123&showTab=like}，
 * yt-dlp Douyin 提取器只认 {@code /video/{id}}，否则会报 Unsupported URL。
 */
public final class VideoUrlNormalizer {

    private static final Pattern DOUYIN_VIDEO_PATH = Pattern.compile(
            "(?i)^https?://(?:www\\.)?(?:ies)?douyin\\.com/(?:share/)?video/(\\d+)");
    private static final Pattern DOUYIN_MODAL_ID = Pattern.compile(
            "(?i)(?:^|[?&])modal_id=(\\d+)");
    private static final Pattern DOUYIN_AWEME_ID = Pattern.compile(
            "(?i)(?:^|[?&])aweme_id=(\\d+)");
    private static final Pattern DOUYIN_NOTE_PATH = Pattern.compile(
            "(?i)^https?://(?:www\\.)?douyin\\.com/note/(\\d+)");

    private VideoUrlNormalizer() {
    }

    /**
     * 规范化视频 URL；无法识别时原样返回 trim 后的输入。
     */
    public static String normalize(String rawUrl) {
        if (rawUrl == null) {
            return null;
        }
        String url = rawUrl.trim();
        if (url.isEmpty()) {
            return url;
        }
        // 去掉首尾常见包裹字符（聊天软件复制时带引号）
        if ((url.startsWith("\"") && url.endsWith("\""))
                || (url.startsWith("'") && url.endsWith("'"))
                || (url.startsWith("<") && url.endsWith(">"))) {
            url = url.substring(1, url.length() - 1).trim();
        }

        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("douyin.com") || lower.contains("iesdouyin.com")) {
            return normalizeDouyin(url);
        }
        return url;
    }

    private static String normalizeDouyin(String url) {
        // 已是标准 /video/{id}
        Matcher videoPath = DOUYIN_VIDEO_PATH.matcher(url);
        if (videoPath.find()) {
            String id = videoPath.group(1);
            return "https://www.douyin.com/video/" + id;
        }

        // /note/{id}（图文/笔记，部分场景 id 与视频互通；转 video 更稳）
        Matcher notePath = DOUYIN_NOTE_PATH.matcher(url);
        if (notePath.find()) {
            return "https://www.douyin.com/video/" + notePath.group(1);
        }

        // user/self?modal_id= / 发现页 / 推荐页等：查询参数里的视频 id
        String id = firstQueryId(url);
        if (id != null) {
            return "https://www.douyin.com/video/" + id;
        }

        // 短链 v.douyin.com / 其它形态交由 yt-dlp 跟随跳转
        return url;
    }

    private static String firstQueryId(String url) {
        Matcher modal = DOUYIN_MODAL_ID.matcher(url);
        if (modal.find()) {
            return modal.group(1);
        }
        Matcher aweme = DOUYIN_AWEME_ID.matcher(url);
        if (aweme.find()) {
            return aweme.group(1);
        }
        // 解析 query 以防特殊编码
        try {
            URI uri = URI.create(url);
            String query = uri.getRawQuery();
            if (query == null || query.isBlank()) {
                return null;
            }
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String val = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                if (("modal_id".equalsIgnoreCase(key) || "aweme_id".equalsIgnoreCase(key))
                        && val != null && val.matches("\\d+")) {
                    return val;
                }
            }
        } catch (Exception ignored) {
            // 非法 URI 时保持原 URL
        }
        return null;
    }
}
