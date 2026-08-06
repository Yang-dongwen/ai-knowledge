package com.dwcode.okxbot.video.util;

import com.dwcode.okxbot.common.exception.BusinessException;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
     * 下载前安全校验：仅 http(s)，拒绝 userInfo 与内网/回环/CGNAT/metadata（fail-closed DNS）。
     * 应在 {@link #normalize(String)} 之后调用。
     * <p>注意：yt-dlp 仍会自行解析 DNS，无法完全消除 rebinding；生产建议配合出口防火墙 / 平台 allowlist。
     */
    public static void assertSafeForDownload(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new BusinessException(400, "视频链接不能为空");
        }
        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (Exception e) {
            throw new BusinessException(400, "无法解析视频链接");
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new BusinessException(400, "仅支持 http/https 视频链接");
        }
        if (uri.getUserInfo() != null && !uri.getUserInfo().isEmpty()) {
            throw new BusinessException(400, "视频链接不允许包含 userInfo");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException(400, "视频链接缺少主机名");
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        String h = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(h)
                || h.endsWith(".localhost")
                || h.endsWith(".local")
                || h.endsWith(".internal")
                || "metadata.google.internal".equals(h)
                || "metadata".equals(h)
                || h.endsWith(".svc.cluster.local")) {
            throw new BusinessException(400, "不允许访问内网或本机地址");
        }
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            if (addrs == null || addrs.length == 0) {
                throw new BusinessException(400, "视频链接主机 DNS 无记录");
            }
            for (InetAddress addr : addrs) {
                if (isBlockedAddress(addr)) {
                    throw new BusinessException(400, "不允许访问内网或本机地址");
                }
            }
        } catch (UnknownHostException e) {
            // fail-closed：避免 check 时失败、yt-dlp 再解析时落到内网
            throw new BusinessException(400, "视频链接主机无法解析");
        } catch (BusinessException e) {
            throw e;
        } catch (SecurityException e) {
            throw new BusinessException(400, "视频链接主机校验失败");
        }
    }

    /**
     * 与文章模块 UrlSafetyGuard 对齐的内网/保留地址判定。
     */
    static boolean isBlockedAddress(InetAddress addr) {
        if (addr == null) {
            return true;
        }
        if (addr.isAnyLocalAddress()
                || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] raw = addr.getAddress();
        if (addr instanceof Inet4Address || (raw != null && raw.length == 4)) {
            return isBlockedIpv4(raw);
        }
        if (addr instanceof Inet6Address || (raw != null && raw.length == 16)) {
            Inet6Address v6 = addr instanceof Inet6Address ? (Inet6Address) addr : null;
            return isBlockedIpv6(raw, v6);
        }
        return true;
    }

    private static boolean isBlockedIpv4(byte[] b) {
        int a = b[0] & 0xFF;
        int b1 = b[1] & 0xFF;
        int c = b[2] & 0xFF;
        int d = b[3] & 0xFF;
        if (a == 0 || a == 127) {
            return true;
        }
        if (a == 10) {
            return true;
        }
        if (a == 172 && b1 >= 16 && b1 <= 31) {
            return true;
        }
        if (a == 192 && b1 == 168) {
            return true;
        }
        // CGNAT 100.64.0.0/10
        if (a == 100 && b1 >= 64 && b1 <= 127) {
            return true;
        }
        // 169.254.0.0/16 link-local + metadata
        if (a == 169 && b1 == 254) {
            return true;
        }
        // 198.18.0.0/15 benchmark
        if (a == 198 && (b1 == 18 || b1 == 19)) {
            return true;
        }
        return false;
    }

    private static boolean isBlockedIpv6(byte[] b, Inet6Address inet6) {
        if (inet6 != null && inet6.isIPv4CompatibleAddress()) {
            return isBlockedIpv4(Arrays.copyOfRange(b, 12, 16));
        }
        boolean mapped = b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 0
                && b[4] == 0 && b[5] == 0 && b[6] == 0 && b[7] == 0
                && b[8] == 0 && b[9] == 0 && b[10] == (byte) 0xFF && b[11] == (byte) 0xFF;
        if (mapped) {
            return isBlockedIpv4(Arrays.copyOfRange(b, 12, 16));
        }
        // fc00::/7 ULA
        int first = b[0] & 0xFE;
        if (first == 0xFC) {
            return true;
        }
        // fe80::/10
        if ((b[0] & 0xFF) == 0xFE && (b[1] & 0xC0) == 0x80) {
            return true;
        }
        return false;
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
