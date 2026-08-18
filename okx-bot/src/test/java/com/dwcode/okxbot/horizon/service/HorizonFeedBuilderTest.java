package com.dwcode.okxbot.horizon.service;

import com.dwcode.okxbot.horizon.dto.HorizonDigestView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HorizonFeedBuilderTest {

    @Test
    void extractsHttpHeadingsAndSkipsTocAnchors() {
        String md = """
                # Horizon 每日速递 - 2026-08-17
                
                **科技新闻**
                1. [目录项](#item-tech-news-1)
                
                ### [Anthropic 的 &#x27;水印&#x27;](https://example.com/a) ⭐️ 7.0/10
                摘要
                
                ### [第二篇](https://example.com/b)
                """;
        var digest = HorizonDigestView.builder()
                .title("Horizon 每日速递 2026-08-17")
                .date("2026-08-17")
                .markdown(md)
                .build();
        var items = HorizonFeedBuilder.items(List.of(digest), "https://dwcode.cloud/news");
        assertEquals(2, items.size());
        assertEquals("Anthropic 的 '水印'", items.get(0).title());
        assertEquals("https://example.com/a", items.get(0).url());
        assertEquals("https://example.com/b", items.get(1).url());
    }

    @Test
    void emptyDigestFallsBackToNewsPage() {
        var digest = HorizonDigestView.builder()
                .title("Horizon 每日速递 2026-08-17")
                .date("2026-08-17")
                .markdown("> 已分析 1 条内容，但没有达到重要性阈值的条目。")
                .snippet("暂无")
                .build();
        var items = HorizonFeedBuilder.items(List.of(digest), "https://dwcode.cloud/news");
        assertEquals(1, items.size());
        assertEquals("https://dwcode.cloud/news", items.get(0).url());
    }

    @Test
    void rssIsWellFormed() {
        var items = List.of(new HorizonFeedBuilder.Item(
                "A & B", "https://example.com/x?q=1", "2026-08-17", "hello"));
        String xml = HorizonFeedBuilder.rss("今日资讯", "https://dwcode.cloud/news", "Horizon", items);
        assertTrue(xml.contains("<title>A &amp; B</title>"));
        assertTrue(xml.contains("<guid isPermaLink=\"true\">https://example.com/x?q=1</guid>"));
        assertFalse(xml.contains("<title>A & B</title>"));
    }
}
