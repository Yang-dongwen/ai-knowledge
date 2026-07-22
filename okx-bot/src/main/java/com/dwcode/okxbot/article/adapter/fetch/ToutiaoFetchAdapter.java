package com.dwcode.okxbot.article.adapter.fetch;

import com.dwcode.okxbot.article.enums.ArticleErrorCode;
import com.dwcode.okxbot.article.port.ArticleFetchCommand;
import com.dwcode.okxbot.article.port.ArticleFetchPort;
import com.dwcode.okxbot.article.port.ArticleFetchResult;
import com.dwcode.okxbot.article.security.PinnedHttpFetcher;
import com.dwcode.okxbot.article.security.UrlSafetyGuard;
import com.dwcode.okxbot.article.security.ArticleSafetyException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 今日头条图文文章抓取。
 * <p>
 * 策略：请求移动端文章页 {@code https://m.toutiao.com/article/{id}/}，
 * 解析内嵌 {@code RENDER_DATA}（URL 编码 JSON）中的 {@code articleInfo.content} 全文 HTML。
 * <p>
 * 非全量逆向签名方案；公开页可用，失败时 pipeline 仍可 NEEDS_PASTE / 粘贴降级。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToutiaoFetchAdapter implements ArticleFetchPort {

    private static final Pattern RENDER_DATA = Pattern.compile(
            "(?is)id\\s*=\\s*[\"']RENDER_DATA[\"'][^>]*>\\s*([^<]+)\\s*<");

    private static final Pattern IMG_SRC = Pattern.compile(
            "(?i)<img[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']");

    private static final Pattern OG_IMAGE = Pattern.compile(
            "(?i)<meta[^>]+(property|name)\\s*=\\s*[\"']og:image[\"'][^>]*content\\s*=\\s*[\"']([^\"']+)[\"']");

    private static final String MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) "
                    + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 "
                    + "Mobile/15E148 Safari/604.1";

    private final PinnedHttpFetcher pinnedHttpFetcher;
    private final UrlSafetyGuard urlSafetyGuard;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String platform) {
        return "toutiao".equalsIgnoreCase(platform);
    }

    @Override
    public boolean isGenericFallback() {
        return false;
    }

    @Override
    public ArticleFetchResult fetch(ArticleFetchCommand cmd) {
        long t0 = System.currentTimeMillis();
        String rawUrl = cmd.getUrl();
        String itemId = extractItemId(rawUrl);
        if (itemId == null) {
            return ArticleFetchResult.fail(ArticleErrorCode.INVALID_URL,
                    "无法从头条链接解析文章 ID: " + rawUrl);
        }

        // 优先移动端页（RENDER_DATA 更完整）
        String[] candidates = {
                "https://m.toutiao.com/article/" + itemId + "/",
                "https://www.toutiao.com/article/" + itemId + "/",
                "https://www.toutiao.com/i" + itemId + "/"
        };

        Exception last = null;
        for (String pageUrl : candidates) {
            try {
                urlSafetyGuard.assertSafeUrl(pageUrl);
                ArticleFetchResult r = fetchOne(pageUrl, itemId, t0);
                if (r.isSuccess()) {
                    return r;
                }
                last = new IllegalStateException(r.getErrorCode() + ": " + r.getErrorMessage());
                log.info("头条候选页失败: {} → {}", pageUrl, r.getErrorCode());
            } catch (Exception e) {
                last = e;
                log.info("头条候选页异常: {} → {}", pageUrl, e.getMessage());
            }
        }
        String msg = last != null ? last.getMessage() : "头条抓取失败";
        return ArticleFetchResult.builder()
                .success(false)
                .errorCode(ArticleErrorCode.PIPELINE_ERROR)
                .errorMessage("头条自动提取失败（" + msg + "），请粘贴正文")
                .latencyMs(System.currentTimeMillis() - t0)
                .build();
    }

    private ArticleFetchResult fetchOne(String pageUrl, String itemId, long t0) {
        try {
            PinnedHttpFetcher.FetchResult raw = pinnedHttpFetcher.get(pageUrl, MOBILE_UA);
            String html = decodeBody(raw);
            if (html == null || html.isBlank()) {
                return ArticleFetchResult.fail(ArticleErrorCode.EMPTY_MAIN_TEXT, "头条页面为空");
            }

            Matcher m = RENDER_DATA.matcher(html);
            if (!m.find()) {
                // 结构变化或页面为壳：尝试全局提取 title + content
                String title = extractTitleFromPage(html);
                String content = extractContentFromPage(html);
                if (title != null || content != null) {
                    List<String> imageUrls = extractImageUrls(html);
                    List<Map<String, Object>> images = extractImages(html);

                    return ArticleFetchResult.builder()
                            .success(true)
                            .finalUrl(pageUrl)
                            .contentType("text/html")
                            .rawHtml(content != null ? content : "<html><body>" + title + "</body></html>")
                            .titleHint(title)
                            .authorHint(extractAuthorFromPage(html))
                            .imageUrls(imageUrls)
                            .images(images)
                            .build();
                }
                return ArticleFetchResult.fail(ArticleErrorCode.EMPTY_MAIN_TEXT,
                        "未找到 RENDER_DATA（页面可能为壳或需登录）");
            }
            String encoded = m.group(1).trim();
            String json = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(json);
            JsonNode info = root.path("articleInfo");
            if (info.isMissingNode() || info.isNull()) {
                // 偶发结构变化：全局搜 content 字段
                info = findArticleInfo(root);
            }
            if (info == null || info.isMissingNode()) {
                return ArticleFetchResult.fail(ArticleErrorCode.EMPTY_MAIN_TEXT,
                        "RENDER_DATA 中无 articleInfo");
            }

            String title = text(info, "title");
            String contentHtml = text(info, "content");
            if (contentHtml == null || contentHtml.isBlank()) {
                // 有的字段在 content 外层
                contentHtml = text(info, "rich_content");
            }
            if (contentHtml == null || contentHtml.isBlank()) {
                return ArticleFetchResult.fail(ArticleErrorCode.EMPTY_MAIN_TEXT,
                        "articleInfo.content 为空");
            }
            String author = firstNonBlank(
                    text(info, "source"),
                    text(info, "detailSource"),
                    text(info.path("mediaInfo"), "name")
            );
            String finalUrl = firstNonBlank(
                    text(info, "url"),
                    "https://www.toutiao.com/article/" + itemId + "/"
            );

            // 包装成完整 HTML 便于 GenericHtmlExtract 抽取
            String wrapped = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>"
                    + escapeHtml(title != null ? title : "")
                    + "</title></head><body><article>"
                    + contentHtml
                    + "</article></body></html>";

            List<String> imageUrls = extractImageUrls(html);
            List<Map<String, Object>> images = extractImages(html);

            log.info("头条文章提取成功: itemId={} titleLen={} contentLen={} images={}",
                    itemId,
                    title != null ? title.length() : 0,
                    contentHtml.length(),
                    images.size());

            return ArticleFetchResult.builder()
                    .success(true)
                    .finalUrl(finalUrl)
                    .contentType("text/html; charset=utf-8")
                    .rawHtml(wrapped)
                    .titleHint(title)
                    .authorHint(author)
                    .httpStatus(raw.getStatusCode())
                    .latencyMs(System.currentTimeMillis() - t0)
                    .imageUrls(imageUrls)
                    .images(images)
                    .build();
        } catch (ArticleSafetyException e) {
            return ArticleFetchResult.builder()
                    .success(false)
                    .errorCode(e.getErrorCode())
                    .errorMessage(e.getMessage())
                    .latencyMs(System.currentTimeMillis() - t0)
                    .build();
        } catch (Exception e) {
            return ArticleFetchResult.builder()
                    .success(false)
                    .errorCode(ArticleErrorCode.PIPELINE_ERROR)
                    .errorMessage(e.getMessage())
                    .latencyMs(System.currentTimeMillis() - t0)
                    .build();
        }
    }

    // 辅助提取方法（fallback 用）
    private static String extractTitleFromPage(String html) {
        // 简单标题提取
        Matcher m = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        if (m.find()) {
            return m.group(1).replaceAll("\\s+", " ").trim();
        }
        return null;
    }

    private static String extractContentFromPage(String html) {
        // 简单内容提取：文章正文或第一个 p
        String body = html;
        // 优先 main 或 article
        Matcher m = Pattern.compile("(?is)<(main|article)[^>]*>(.*?)</\\1>").matcher(body);
        if (m.find()) {
            String content = m.group(2);
            content = content.replaceAll("(?is)<script[^>]*>.*?</script>", "");
            content = content.replaceAll("(?is)<style[^>]*>.*?</style>", "");
            content = content.replaceAll("(?is)<nav[^>]*>.*?</nav>", "");
            content = content.replaceAll("(?is)<footer[^>]*>.*?</footer>", "");
            content = content.replaceAll("(?is)<header[^>]*>.*?</header>", "");
            content = content.replaceAll("(?is)<aside[^>]*>.*?</aside>", "");
            content = content.replaceAll("(?is)<iframe[^>]*>.*?</iframe>", "");
            return content;
        }
        // 回退：所有 p
        m = Pattern.compile("(?is)<p[^>]*>(.*?)</p>").matcher(body);
        if (m.find()) {
            return m.group(1).replaceAll("\\s+", " ").trim();
        }
        return body;
    }

    private static String extractAuthorFromPage(String html) {
        Matcher m = Pattern.compile("(?is)<meta[^>]+(author|byline)[^>]*content\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>").matcher(html);
        if (m.find()) {
            return m.group(2).trim();
        }
        return null;
    }

    private List<String> extractImageUrls(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        Matcher m = IMG_SRC.matcher(html);
        List<String> urls = new ArrayList<>();
        while (m.find()) {
            String url = m.group(1);
            if (!url.isBlank()) {
                urls.add(url);
            }
        }
        return urls;
    }

    private List<Map<String, Object>> extractImages(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }

        List<Map<String, Object>> images = new ArrayList<>();
        Matcher m = IMG_SRC.matcher(html);

        while (m.find()) {
            Map<String, Object> img = new HashMap<>();
            img.put("src", m.group(1));
            img.put("alt", "");

            // Extract alt attribute
            Matcher altM = Pattern.compile("(?i)alt\\s*=\\s*[\"']([^\"']+)[\"']").matcher(html);
            if (altM.find()) {
                img.put("alt", altM.group(1));
            }

            // Extract width and height
            Matcher sizeM = Pattern.compile("(?i)(?:width|height)\\s*=\\s*[\"'](\\d+)[\"']").matcher(html);
            if (sizeM.find()) {
                img.put("width", sizeM.group(1));
            }
            if (sizeM.find() && sizeM.start() > m.start()) {
                img.put("height", sizeM.group(1));
            }

            images.add(img);
        }

        // Also extract og:image meta tags
        Matcher ogM = OG_IMAGE.matcher(html);
        while (ogM.find()) {
            Map<String, Object> img = new HashMap<>();
            img.put("src", ogM.group(2));
            img.put("alt", "");
            img.put("type", "og_image");
            images.add(img);
        }

        return images;
    }

    private static String decodeBody(PinnedHttpFetcher.FetchResult raw) {
        if (raw == null || raw.getBody() == null) {
            return null;
        }
        return new String(raw.getBody(), StandardCharsets.UTF_8);
    }

    private static JsonNode findArticleInfo(JsonNode root) {
        if (root == null) {
            return null;
        }
        if (root.has("articleInfo")) {
            return root.get("articleInfo");
        }
        if (root.isObject()) {
            var it = root.fields();
            while (it.hasNext()) {
                var e = it.next();
                JsonNode found = findArticleInfo(e.getValue());
                if (found != null) {
                    return found;
                }
            }
        } else if (root.isArray()) {
            for (JsonNode n : root) {
                JsonNode found = findArticleInfo(n);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String text(JsonNode n, String field) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText(null);
        return s != null && !s.isBlank() ? s : null;
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * 从各类头条 URL 中解析文章数字 ID。
     */
    public static String extractItemId(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.contains("toutiao.com") && !lower.contains("ixigua.com")) {
            return null;
        }
        Matcher m = Pattern.compile("(?i)/(?:article|group|item|a|i|w)/?(\\d{10,})").matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        m = Pattern.compile("(?i)article/(\\d{10,})").matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        m = Pattern.compile("(?i)(?:group_id|item_id|article_id)=(\\d{10,})").matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        if (url.matches("\\d{10,}")) {
            return url;
        }
        return null;
    }
}