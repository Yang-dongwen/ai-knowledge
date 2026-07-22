package com.dwcode.okxbot.article.adapter.fetch;

import com.dwcode.okxbot.article.config.ArticleProperties;
import com.dwcode.okxbot.article.enums.ArticleErrorCode;
import com.dwcode.okxbot.article.port.ArticleFetchCommand;
import com.dwcode.okxbot.article.port.ArticleFetchResult;
import com.dwcode.okxbot.article.security.PinnedHttpFetcher;
import com.dwcode.okxbot.article.security.UrlSafetyGuard;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericHtmlFetchAdapterTest {

    private MockWebServer server;
    private GenericHtmlFetchAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        ArticleProperties props = new ArticleProperties();
        props.getSafety().setAllowLoopback(true);
        props.getFetch().setMaxBytes(500_000);
        InetAddress loop = InetAddress.getByName("127.0.0.1");
        UrlSafetyGuard guard = new UrlSafetyGuard(props, host -> new InetAddress[]{loop});
        PinnedHttpFetcher fetcher = new PinnedHttpFetcher(guard, props);
        adapter = new GenericHtmlFetchAdapter(fetcher, guard, props);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void fetchHtmlOk() {
        String body = "<html><head><title>Hello</title></head><body><p>world</p></body></html>";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(body));

        ArticleFetchResult r = adapter.fetch(ArticleFetchCommand.builder()
                .url(server.url("/a").toString())
                .platform("generic")
                .build());
        assertTrue(r.isSuccess());
        assertTrue(r.getRawHtml().contains("world"));
        assertEquals("Hello", r.getTitleHint());
    }

    @Test
    void unsupportedJsonType() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"a\":1}"));

        ArticleFetchResult r = adapter.fetch(ArticleFetchCommand.builder()
                .url(server.url("/j").toString())
                .platform("generic")
                .build());
        assertFalse(r.isSuccess());
        assertEquals(ArticleErrorCode.UNSUPPORTED_CONTENT_TYPE, r.getErrorCode());
    }

    @Test
    void plainTextOk() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .setBody("hello plain"));

        ArticleFetchResult r = adapter.fetch(ArticleFetchCommand.builder()
                .url(server.url("/t").toString())
                .platform("generic")
                .build());
        assertTrue(r.isSuccess());
        assertEquals("hello plain", r.getRawText());
    }
}
