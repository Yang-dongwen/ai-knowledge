package com.dwcode.okxbot.article.adapter.fetch;

import com.dwcode.okxbot.article.port.ArticleFetchResult;
import com.dwcode.okxbot.article.security.PinnedHttpFetcher;
import com.dwcode.okxbot.article.security.UrlSafetyGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToutiaoFetchAdapterTest {

    @Test
    void extractItemIdFromCommonUrls() {
        assertEquals("7664593441992524297",
                ToutiaoFetchAdapter.extractItemId("https://www.toutiao.com/article/7664593441992524297/"));
        assertEquals("7664593441992524297",
                ToutiaoFetchAdapter.extractItemId("https://m.toutiao.com/i7664593441992524297/"));
        assertEquals("7664593441992524297",
                ToutiaoFetchAdapter.extractItemId("https://www.toutiao.com/group/7664593441992524297/"));
        assertEquals("7664593441992524297",
                ToutiaoFetchAdapter.extractItemId("https://www.toutiao.com/a7664593441992524297/"));
    }

    @Test
    void parseRenderDataToArticle() throws Exception {
        String contentHtml = "<p>第一段正文内容足够长用于抽取验证。</p><p>第二段继续补充说明。</p>";
        String renderJson = """
                {"articleInfo":{"title":"测试头条标题","content":%s,"source":"测试号","url":"https://www.toutiao.com/article/1234567890123456789/"}}
                """.formatted(new ObjectMapper().writeValueAsString(contentHtml));
        String encoded = URLEncoder.encode(renderJson, StandardCharsets.UTF_8);
        String html = "<html><body><script id=\"RENDER_DATA\" type=\"application/json\">"
                + encoded + "</script></body></html>";

        PinnedHttpFetcher fetcher = mock(PinnedHttpFetcher.class);
        PinnedHttpFetcher.FetchResult fr = new PinnedHttpFetcher.FetchResult();
        fr.setStatusCode(200);
        fr.setBody(html.getBytes(StandardCharsets.UTF_8));
        fr.setFinalUrl("https://m.toutiao.com/article/1234567890123456789/");
        when(fetcher.get(anyString(), anyString())).thenReturn(fr);

        UrlSafetyGuard guard = mock(UrlSafetyGuard.class);
        when(guard.assertSafeUrl(anyString())).thenAnswer(inv -> null);

        ToutiaoFetchAdapter adapter = new ToutiaoFetchAdapter(fetcher, guard, new ObjectMapper());
        ArticleFetchResult r = adapter.fetch(
                com.dwcode.okxbot.article.port.ArticleFetchCommand.builder()
                        .url("https://www.toutiao.com/article/1234567890123456789/")
                        .platform("toutiao")
                        .build());

        assertTrue(r.isSuccess(), r.getErrorMessage());
        assertEquals("测试头条标题", r.getTitleHint());
        assertEquals("测试号", r.getAuthorHint());
        assertNotNull(r.getRawHtml());
        assertTrue(r.getRawHtml().contains("第一段正文"));
    }

    @Test
    void supportsToutiaoOnly() {
        ToutiaoFetchAdapter adapter = new ToutiaoFetchAdapter(
                mock(PinnedHttpFetcher.class), mock(UrlSafetyGuard.class), new ObjectMapper());
        assertTrue(adapter.supports("toutiao"));
        assertFalse(adapter.supports("generic"));
        assertFalse(adapter.isGenericFallback());
    }
}
