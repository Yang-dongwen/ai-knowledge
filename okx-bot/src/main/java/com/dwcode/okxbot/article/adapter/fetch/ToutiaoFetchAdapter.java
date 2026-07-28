package com.dwcode.okxbot.article.adapter.fetch;

import com.dwcode.okxbot.article.enums.ArticleErrorCode;
import com.dwcode.okxbot.article.port.ArticleFetchCommand;
import com.dwcode.okxbot.article.port.ArticleFetchPort;
import com.dwcode.okxbot.article.port.ArticleFetchResult;
import com.dwcode.okxbot.article.security.ArticleSafetyException;
import com.dwcode.okxbot.article.security.PinnedHttpFetcher;
import com.dwcode.okxbot.article.security.UrlSafetyGuard;
import com.dwcode.okxbot.article.util.ArticleUrlNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 今日头条图文文章抓取。
 * <p>
 * 策略：请求移动端文章页 {@code https://m.toutiao.com/article/{id}/}，
 * 解析内嵌 {@code RENDER_DATA}（URL 编码 JSON）中的 {@code articleInfo.content} 全文 HTML。
 * 失败时 pipeline 可 NEEDS_PASTE / 粘贴降级。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToutiaoFetchAdapter implements ArticleFetchPort {

    private static final Pattern RENDER_DATA = Pattern.compile(
            "(?is)id\\s*=\\s*[\"']RENDER_DATA[\"'][^>]*>\\s*([^<]+)\\s*<");

    private static final Pattern TITLE_TAG = Pattern.compile(
            "(?is)<title[^>]*>(.*?)</title>");

    private static final Pattern MAIN_OR_ARTICLE = Pattern.compile(
            "(?is)<(main|article)[^>]*>(.*?)</\\1>");

    private static final Pattern P_TAG = Pattern.compile(
            "(?is)<p[^>]*>(.*?)</p>");

    private static final Pattern META_AUTHOR = Pattern.compile(
            "(?is)<meta[^>]+(author|byline)[^>]*content\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>");

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
                String title = extractTitleFromPage(html);
                String content = extractContentFromPage(html);
                if (title != null || content != null) {
                    return ArticleFetchResult.builder()
                            .success(true)
                            .finalUrl(pageUrl)
                            .contentType("text/html")
                            .rawHtml(content != null ? content : "<html><body>" + title + "</body></html>")
                            .titleHint(title)
                            .authorHint(extractAuthorFromPage(html))
                            .httpStatus(raw.getStatusCode())
                            .latencyMs(System.currentTimeMillis() - t0)
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
                info = findArticleInfo(root);
            }
            if (info == null || info.isMissingNode()) {
                return ArticleFetchResult.fail(ArticleErrorCode.EMPTY_MAIN_TEXT,
                        "RENDER_DATA 中无 articleInfo");
            }

            String title = text(info, "title");
            String contentHtml = text(info, "content");
            if (contentHtml == null || contentHtml.isBlank()) {
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

            String wrapped = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>"
                    + escapeHtml(title != null ? title : "")
                    + "</title></head><body><article>"
                    + contentHtml
                    + "</article></body></html>";

            log.info("头条文章提取成功: itemId={} titleLen={} contentLen={}",
                    itemId,
                    title != null ? title.length() : 0,
                    contentHtml.length());

            return ArticleFetchResult.builder()
                    .success(true)
                    .finalUrl(finalUrl)
                    .contentType("text/html; charset=utf-8")
                    .rawHtml(wrapped)
                    .titleHint(title)
                    .authorHint(author)
                    .httpStatus(raw.getStatusCode())
                    .latencyMs(System.currentTimeMillis() - t0)
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

    private static String extractTitleFromPage(String html) {
        Matcher m = TITLE_TAG.matcher(html);
        if (m.find()) {
            return m.group(1).replaceAll("\\s+", " ").trim();
        }
        return null;
    }

    private static String extractContentFromPage(String html) {
        Matcher m = MAIN_OR_ARTICLE.matcher(html);
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
        m = P_TAG.matcher(html);
        if (m.find()) {
            return m.group(1).replaceAll("\\s+", " ").trim();
        }
        return null;
    }

    private static String extractAuthorFromPage(String html) {
        Matcher m = META_AUTHOR.matcher(html);
        if (m.find()) {
            return m.group(2).trim();
        }
        return null;
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
        return ArticleUrlNormalizer.extractToutiaoId(url);
    }
}
