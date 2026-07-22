package com.dwcode.okxbot.article.adapter.fetch;

import com.dwcode.okxbot.article.config.ArticleProperties;
import com.dwcode.okxbot.article.enums.ArticleErrorCode;
import com.dwcode.okxbot.article.port.ArticleFetchCommand;
import com.dwcode.okxbot.article.port.ArticleFetchPort;
import com.dwcode.okxbot.article.port.ArticleFetchResult;
import com.dwcode.okxbot.article.security.ArticleSafetyException;
import com.dwcode.okxbot.article.security.PinnedHttpFetcher;
import com.dwcode.okxbot.article.security.UrlSafetyGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用 HTML/纯文本抓取（DNS 钉扎 + SSRF）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenericHtmlFetchAdapter implements ArticleFetchPort {

    private static final Pattern CHARSET_IN_META = Pattern.compile(
            "(?i)<meta[^>]+charset\\s*=\\s*[\"']?([a-zA-Z0-9_\\-]+)");
    private static final Pattern TITLE_TAG = Pattern.compile(
            "(?is)<title[^>]*>(.*?)</title>");

    private final PinnedHttpFetcher pinnedHttpFetcher;
    private final UrlSafetyGuard urlSafetyGuard;
    private final ArticleProperties properties;

    @Override
    public boolean supports(String platform) {
        // 专用 Adapter 优先；本类仅作 generic 回落
        return "generic".equalsIgnoreCase(platform) || "other".equalsIgnoreCase(platform);
    }

    @Override
    public boolean isGenericFallback() {
        return true;
    }

    @Override
    public ArticleFetchResult fetch(ArticleFetchCommand cmd) {
        long t0 = System.currentTimeMillis();
        String url = cmd.getUrl();
        if (url == null || url.isBlank()) {
            return ArticleFetchResult.fail(ArticleErrorCode.INVALID_URL, "URL 为空");
        }
        try {
            // 再过一次 SSRF（创建时已检；redirect 内 fetcher 也会检）
            urlSafetyGuard.assertSafeUrl(url);
            PinnedHttpFetcher.FetchResult raw = pinnedHttpFetcher.get(url);
            long latency = System.currentTimeMillis() - t0;

            String contentType = raw.getContentType() != null ? raw.getContentType() : "";
            String ctLower = contentType.toLowerCase(Locale.ROOT);
            byte[] body = raw.getBody() != null ? raw.getBody() : new byte[0];

            Charset charset = detectCharset(ctLower, body);
            String text = new String(body, charset);

            if (isHtml(ctLower, text)) {
                String title = extractTitle(text);
                return ArticleFetchResult.builder()
                        .success(true)
                        .finalUrl(raw.getFinalUrl())
                        .contentType(contentType)
                        .rawHtml(text)
                        .titleHint(title)
                        .httpStatus(raw.getStatusCode())
                        .latencyMs(latency)
                        .build();
            }
            if (isPlain(ctLower)) {
                return ArticleFetchResult.builder()
                        .success(true)
                        .finalUrl(raw.getFinalUrl())
                        .contentType(contentType)
                        .rawText(text)
                        .httpStatus(raw.getStatusCode())
                        .latencyMs(latency)
                        .build();
            }
            // 无 Content-Type 时启发式 HTML
            if (contentType.isBlank() && looksLikeHtml(text)) {
                return ArticleFetchResult.builder()
                        .success(true)
                        .finalUrl(raw.getFinalUrl())
                        .contentType("text/html")
                        .rawHtml(text)
                        .titleHint(extractTitle(text))
                        .httpStatus(raw.getStatusCode())
                        .latencyMs(latency)
                        .build();
            }
            return ArticleFetchResult.builder()
                    .success(false)
                    .errorCode(ArticleErrorCode.UNSUPPORTED_CONTENT_TYPE)
                    .errorMessage("不支持的 Content-Type: " + (contentType.isBlank() ? "(empty)" : contentType))
                    .finalUrl(raw.getFinalUrl())
                    .contentType(contentType)
                    .httpStatus(raw.getStatusCode())
                    .latencyMs(latency)
                    .build();
        } catch (ArticleSafetyException e) {
            return ArticleFetchResult.builder()
                    .success(false)
                    .errorCode(e.getErrorCode())
                    .errorMessage(e.getMessage())
                    .latencyMs(System.currentTimeMillis() - t0)
                    .build();
        } catch (Exception e) {
            log.warn("GenericHtml 抓取失败: {} — {}", url, e.getMessage());
            return ArticleFetchResult.builder()
                    .success(false)
                    .errorCode(ArticleErrorCode.PIPELINE_ERROR)
                    .errorMessage(e.getMessage())
                    .latencyMs(System.currentTimeMillis() - t0)
                    .build();
        }
    }

    private boolean isHtml(String ctLower, String body) {
        List<String> accepted = properties.getFetch().getAcceptContentTypes();
        if (accepted != null) {
            for (String a : accepted) {
                if (a != null && ctLower.contains(a.toLowerCase(Locale.ROOT).split(";")[0].trim())
                        && (a.toLowerCase(Locale.ROOT).contains("html") || a.toLowerCase(Locale.ROOT).contains("xhtml"))) {
                    return true;
                }
            }
        }
        return ctLower.contains("text/html") || ctLower.contains("application/xhtml");
    }

    private boolean isPlain(String ctLower) {
        return ctLower.contains("text/plain");
    }

    private static boolean looksLikeHtml(String text) {
        if (text == null || text.length() < 15) {
            return false;
        }
        String head = text.substring(0, Math.min(500, text.length())).toLowerCase(Locale.ROOT);
        return head.contains("<html") || head.contains("<!doctype html") || head.contains("<body");
    }

    private static Charset detectCharset(String contentType, byte[] body) {
        if (contentType != null) {
            int idx = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
            if (idx >= 0) {
                String cs = contentType.substring(idx + 8).trim().replace("\"", "");
                int semi = cs.indexOf(';');
                if (semi > 0) {
                    cs = cs.substring(0, semi).trim();
                }
                try {
                    return Charset.forName(cs);
                } catch (Exception ignored) {
                    // fall through
                }
            }
        }
        // meta charset
        String probe = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.ISO_8859_1);
        Matcher m = CHARSET_IN_META.matcher(probe);
        if (m.find()) {
            try {
                return Charset.forName(m.group(1).trim());
            } catch (Exception ignored) {
                // fall through
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String extractTitle(String html) {
        if (html == null) {
            return null;
        }
        Matcher m = TITLE_TAG.matcher(html);
        if (m.find()) {
            return m.group(1).replaceAll("\\s+", " ").trim();
        }
        return null;
    }
}
