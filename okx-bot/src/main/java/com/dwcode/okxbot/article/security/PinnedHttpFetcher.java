package com.dwcode.okxbot.article.security;

import com.dwcode.okxbot.article.config.ArticleProperties;
import com.dwcode.okxbot.article.enums.ArticleErrorCode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * 带 DNS 钉扎的 HTTP GET（手动跟随 redirect，每跳重新 {@link UrlSafetyGuard#assertSafeUrl}）。
 * <p>供 PR-4 抓取复用；PR-2 用单测覆盖 redirect / max-bytes。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PinnedHttpFetcher {

    private final UrlSafetyGuard urlSafetyGuard;
    private final ArticleProperties articleProperties;

    /**
     * GET 文本类资源；自动处理 gzip（计数在解压后）。
     */
    public FetchResult get(String rawUrl) {
        return get(rawUrl, null);
    }

    /**
     * @param userAgentOverride 非空时覆盖默认 UA（如头条移动端页）
     */
    public FetchResult get(String rawUrl, String userAgentOverride) {
        SafeUrl safe = urlSafetyGuard.assertSafeUrl(rawUrl);
        return get(safe, userAgentOverride);
    }

    public FetchResult get(SafeUrl initial) {
        return get(initial, null);
    }

    public FetchResult get(SafeUrl initial, String userAgentOverride) {
        ArticleProperties.Fetch cfg = articleProperties.getFetch();
        int maxRedirects = Math.max(0, cfg.getMaxRedirects());
        long maxBytes = Math.max(1, cfg.getMaxBytes());
        int connectMs = Math.max(1000, cfg.getConnectTimeoutMs());
        int readMs = Math.max(1000, cfg.getReadTimeoutMs());
        String ua = (userAgentOverride != null && !userAgentOverride.isBlank())
                ? userAgentOverride
                : (cfg.getUserAgent() != null ? cfg.getUserAgent() : "okx-bot-article-bot/1.0");

        SafeUrl current = initial;
        List<String> hopTrace = new ArrayList<>();
        hopTrace.add(current.getUrlString());

        for (int hop = 0; hop <= maxRedirects; hop++) {
            OkHttpClient client = new OkHttpClient.Builder()
                    .dns(urlSafetyGuard.createPinnedDns(current))
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .connectTimeout(Duration.ofMillis(connectMs))
                    .readTimeout(Duration.ofMillis(readMs))
                    .callTimeout(Duration.ofMillis(connectMs + readMs + 5_000L))
                    .build();

            Request request = new Request.Builder()
                    .url(current.getUrlString())
                    .header("User-Agent", ua)
                    .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.1")
                    .header("Accept-Encoding", "gzip")
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                int code = response.code();
                String location = response.header("Location");
                if (isRedirect(code)) {
                    if (hop >= maxRedirects) {
                        throw new ArticleSafetyException(ArticleErrorCode.PIPELINE_ERROR,
                                "redirect 次数超过上限: " + maxRedirects);
                    }
                    if (location == null || location.isBlank()) {
                        throw new ArticleSafetyException(ArticleErrorCode.INVALID_URL,
                                "HTTP " + code + " 无 Location");
                    }
                    current = urlSafetyGuard.assertSafeRedirect(current, location);
                    hopTrace.add(current.getUrlString());
                    continue;
                }

                if (code == 403) {
                    throw new ArticleSafetyException(ArticleErrorCode.HTTP_403, "HTTP 403 Forbidden");
                }
                if (code == 404) {
                    throw new ArticleSafetyException(ArticleErrorCode.HTTP_404, "HTTP 404 Not Found");
                }
                if (code < 200 || code >= 300) {
                    throw new ArticleSafetyException(ArticleErrorCode.PIPELINE_ERROR,
                            "HTTP " + code + " 抓取失败");
                }

                ResponseBody body = response.body();
                if (body == null) {
                    throw new ArticleSafetyException(ArticleErrorCode.EMPTY_MAIN_TEXT, "响应体为空");
                }

                String contentType = response.header("Content-Type");
                String contentEncoding = response.header("Content-Encoding");
                byte[] bytes = readLimited(body, contentEncoding, maxBytes);

                FetchResult result = new FetchResult();
                result.setFinalUrl(current.getUrlString());
                result.setStatusCode(code);
                result.setContentType(contentType);
                result.setBody(bytes);
                result.setHopTrace(List.copyOf(hopTrace));
                result.setPinnedHost(current.getHost());
                return result;
            } catch (ArticleSafetyException e) {
                throw e;
            } catch (java.net.SocketTimeoutException e) {
                throw new ArticleSafetyException(ArticleErrorCode.TIMEOUT, "请求超时: " + e.getMessage());
            } catch (IOException e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (msg.toLowerCase(Locale.ROOT).contains("timeout")) {
                    throw new ArticleSafetyException(ArticleErrorCode.TIMEOUT, "请求超时: " + msg);
                }
                throw new ArticleSafetyException(ArticleErrorCode.PIPELINE_ERROR, "HTTP 请求失败: " + msg);
            }
        }
        throw new ArticleSafetyException(ArticleErrorCode.PIPELINE_ERROR, "redirect 循环或超限");
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private static byte[] readLimited(ResponseBody body, String contentEncoding, long maxBytes)
            throws IOException {
        InputStream raw = body.byteStream();
        InputStream in = raw;
        if (contentEncoding != null && contentEncoding.toLowerCase(Locale.ROOT).contains("gzip")) {
            in = new GZIPInputStream(raw);
        }
        try (CountingInputStream counting = new CountingInputStream(in, maxBytes);
             ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = counting.read(chunk)) >= 0) {
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        } catch (ArticleSafetyException e) {
            throw e;
        }
    }

    @Data
    public static class FetchResult {
        private String finalUrl;
        private int statusCode;
        private String contentType;
        private byte[] body;
        private List<String> hopTrace;
        private String pinnedHost;
    }
}
