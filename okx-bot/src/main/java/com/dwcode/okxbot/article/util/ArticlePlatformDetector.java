package com.dwcode.okxbot.article.util;

import com.dwcode.okxbot.article.enums.ArticleSupportLevel;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 文章 URL 平台识别 + supportLevel（与设计 §5 对齐）。
 * <p>独立于 video {@code StorageService.detectPlatform}。
 */
@Component
public class ArticlePlatformDetector {

    /**
     * 识别平台；url 空返回 generic/UNSUPPORTED。
     */
    public ArticlePlatformInfo detect(String rawUrl) {
        String url = ArticleUrlNormalizer.normalize(rawUrl);
        if (url == null || url.isBlank()) {
            return ArticlePlatformInfo.builder()
                    .platform("other")
                    .supportLevel(ArticleSupportLevel.UNSUPPORTED)
                    .message("URL 为空")
                    .build();
        }
        String host = ArticleUrlNormalizer.extractHost(url);
        String lower = url.toLowerCase(Locale.ROOT);
        String hostLower = host != null ? host.toLowerCase(Locale.ROOT) : "";

        // 小红书 — PASTE_ONLY（默认不抓）
        if (hostMatches(hostLower, "xiaohongshu.com", "xhslink.com", "xhscdn.com")
                || lower.contains("xiaohongshu.com") || lower.contains("xhslink.com")) {
            return info("xiaohongshu", ArticleSupportLevel.PASTE_ONLY, host,
                    "小红书图文默认不开放自动抓取，请粘贴正文");
        }

        // 头条图文：尝试 RENDER_DATA 自动提取；西瓜视频等仍可能失败
        if (hostMatches(hostLower, "toutiao.com", "toutiaoimg.com")
                || lower.contains("toutiao.com")) {
            // 纯视频站 ixigua 另案；article/group 图文可试
            return info("toutiao", ArticleSupportLevel.PARTIAL, host,
                    "头条图文将尝试自动提取；失败请粘贴正文");
        }
        if (hostMatches(hostLower, "ixigua.com") || lower.contains("ixigua.com")) {
            return info("ixigua", ArticleSupportLevel.UNSUPPORTED, host,
                    "西瓜视频请使用视频提取工具");
        }

        // X / Twitter
        if (hostMatches(hostLower, "x.com", "twitter.com", "t.co", "mobile.twitter.com")
                || lower.contains("://x.com/") || lower.contains("twitter.com/")) {
            return info("x", ArticleSupportLevel.PASTE_ONLY, host,
                    "X/Twitter 文本帖默认请粘贴正文（或后续官方 API）");
        }

        // 微信公众号 — PARTIAL（可试公开页）
        if (hostMatches(hostLower, "mp.weixin.qq.com") || lower.contains("mp.weixin.qq.com")) {
            return info("weixin", ArticleSupportLevel.PARTIAL, host,
                    "微信公众号可能需登录或失败，建议准备粘贴正文");
        }

        // 知乎
        if (hostMatches(hostLower, "zhihu.com", "zhimg.com") || lower.contains("zhihu.com")) {
            return info("zhihu", ArticleSupportLevel.PARTIAL, host,
                    "知乎公开页可能可提取，失败请粘贴");
        }

        // 微博
        if (hostMatches(hostLower, "weibo.com", "weibo.cn", "sina.com.cn")
                || lower.contains("weibo.com") || lower.contains("weibo.cn")) {
            return info("weibo", ArticleSupportLevel.PARTIAL, host,
                    "微博公开页可能可提取，失败请粘贴");
        }

        // B 站专栏（文章，非视频）
        if ((hostMatches(hostLower, "bilibili.com", "b23.tv") || lower.contains("bilibili.com"))
                && (lower.contains("/read/") || lower.contains("read/cv") || lower.contains("article"))) {
            return info("bilibili_column", ArticleSupportLevel.PARTIAL, host,
                    "B 站专栏可尝试提取；视频请用视频提取工具");
        }
        if (hostMatches(hostLower, "bilibili.com", "b23.tv") || lower.contains("bilibili.com")) {
            // 视频站：文章模块不支持自动抓（引导 video）
            return info("bilibili", ArticleSupportLevel.UNSUPPORTED, host,
                    "B 站视频请使用视频提取；专栏文章链接需含 /read/");
        }

        // 抖音等视频站 — 非文章主路径
        if (hostMatches(hostLower, "douyin.com", "iesdouyin.com", "tiktok.com")
                || lower.contains("douyin.com")) {
            return info("douyin", ArticleSupportLevel.UNSUPPORTED, host,
                    "短视频请使用视频提取工具");
        }

        // YouTube
        if (hostMatches(hostLower, "youtube.com", "youtu.be") || lower.contains("youtube.com")) {
            return info("youtube", ArticleSupportLevel.UNSUPPORTED, host,
                    "视频请使用视频提取工具");
        }

        // 通用网页
        if (host != null && !host.isBlank()) {
            return info("generic", ArticleSupportLevel.FULL, host,
                    "通用网页，可尝试自动提取");
        }

        return info("other", ArticleSupportLevel.UNSUPPORTED, host, "无法识别平台，请粘贴正文");
    }

    /**
     * PASTE_ONLY / UNSUPPORTED 时服务端不得发起抓取。
     */
    public boolean shouldSkipFetch(ArticleSupportLevel level) {
        return level == ArticleSupportLevel.PASTE_ONLY || level == ArticleSupportLevel.UNSUPPORTED;
    }

    private static ArticlePlatformInfo info(String platform, ArticleSupportLevel level,
                                            String host, String message) {
        return ArticlePlatformInfo.builder()
                .platform(platform)
                .supportLevel(level)
                .host(host)
                .message(message)
                .build();
    }

    private static boolean hostMatches(String host, String... suffixes) {
        if (host == null || host.isBlank()) {
            return false;
        }
        for (String s : suffixes) {
            if (host.equals(s) || host.endsWith("." + s)) {
                return true;
            }
        }
        return false;
    }
}
