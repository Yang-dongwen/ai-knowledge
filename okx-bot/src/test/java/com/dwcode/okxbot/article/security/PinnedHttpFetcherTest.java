package com.dwcode.okxbot.article.security;

import com.dwcode.okxbot.article.config.ArticleProperties;
import com.dwcode.okxbot.article.enums.ArticleErrorCode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉扎 HTTP 抓取：mock server（allowLoopback 仅测试开启）。
 */
class PinnedHttpFetcherTest {

    private MockWebServer server;
    private ArticleProperties props;
    private UrlSafetyGuard guard;
    private PinnedHttpFetcher fetcher;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        props = new ArticleProperties();
        // MockWebServer 绑定 loopback；生产默认 false
        props.getSafety().setAllowLoopback(true);
        props.getFetch().setMaxBytes(1024);
        props.getFetch().setMaxRedirects(3);
        props.getFetch().setConnectTimeoutMs(3000);
        props.getFetch().setReadTimeoutMs(3000);

        InetAddress loop = InetAddress.getByName("127.0.0.1");
        DnsResolver localDns = host -> new InetAddress[]{loop};
        guard = new UrlSafetyGuard(props, localDns);
        fetcher = new PinnedHttpFetcher(guard, props);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void fetchPublicHtmlOk() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><body>hello article</body></html>"));

        String url = server.url("/news/1").toString();
        PinnedHttpFetcher.FetchResult result = fetcher.get(url);
        assertEquals(200, result.getStatusCode());
        assertTrue(new String(result.getBody(), StandardCharsets.UTF_8).contains("hello article"));
        assertTrue(result.getContentType().contains("text/html"));
    }

    @Test
    void redirectToIntranetBlocked() {
        // 第一跳到 mock，第二跳 Location 指向 10.x
        server.enqueue(new MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "http://10.0.0.9/internal"));

        String url = server.url("/start").toString();
        ArticleSafetyException ex = assertThrows(ArticleSafetyException.class, () -> fetcher.get(url));
        assertEquals(ArticleErrorCode.SSRF_BLOCKED, ex.getErrorCode());
    }

    @Test
    void payloadTooLarge() {
        byte[] big = new byte[2048];
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody(new String(big, StandardCharsets.ISO_8859_1)));

        String url = server.url("/big").toString();
        ArticleSafetyException ex = assertThrows(ArticleSafetyException.class, () -> fetcher.get(url));
        assertEquals(ArticleErrorCode.PAYLOAD_TOO_LARGE, ex.getErrorCode());
    }

    @Test
    void followSafeRedirect() {
        server.enqueue(new MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/final").toString()));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("ok-final"));

        String url = server.url("/start").toString();
        PinnedHttpFetcher.FetchResult result = fetcher.get(url);
        assertEquals(200, result.getStatusCode());
        assertEquals("ok-final", new String(result.getBody(), StandardCharsets.UTF_8));
        assertTrue(result.getHopTrace().size() >= 2);
    }
}
