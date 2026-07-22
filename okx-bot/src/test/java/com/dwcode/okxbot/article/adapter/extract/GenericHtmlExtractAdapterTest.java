package com.dwcode.okxbot.article.adapter.extract;

import com.dwcode.okxbot.article.config.ArticleProperties;
import com.dwcode.okxbot.article.port.ArticleFetchResult;
import com.dwcode.okxbot.article.port.MainTextDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericHtmlExtractAdapterTest {

    private GenericHtmlExtractAdapter adapter;

    @BeforeEach
    void setUp() {
        ArticleProperties props = new ArticleProperties();
        props.getExtract().setMinQualityScore(0.2);
        props.getExtract().setMaxMainTextChars(50_000);
        adapter = new GenericHtmlExtractAdapter(props);
    }

    @Test
    void extractArticleMain() {
        String html = """
                <html><head><title>测试新闻标题</title></head>
                <body>
                <nav>导航广告</nav>
                <article>
                <h1>测试新闻标题</h1>
                <p>这是第一段正文，包含足够多的中文内容用于质量评分与抽取验证，长度需要超过一百个字符以上才比较稳妥，因此继续补充一些句子。</p>
                <p>这是第二段正文，继续说明事件背景与关键细节，避免被判定为低质量页面或广告噪音区域。</p>
                <p>这是第三段，给出结论与后续展望，方便二次创作与核心要点提取链路使用。</p>
                </article>
                <footer>版权所有</footer>
                </body></html>
                """;
        ArticleFetchResult raw = ArticleFetchResult.builder()
                .success(true)
                .rawHtml(html)
                .contentType("text/html")
                .titleHint("测试新闻标题")
                .build();
        MainTextDocument doc = adapter.extract(raw);
        assertFalse(doc.isUnusable(), "should be usable: " + doc.getUnusableReason());
        assertTrue(doc.getMainText().contains("第一段正文"));
        assertTrue(doc.getMainText().contains("第二段正文"));
        assertFalse(doc.getMainText().contains("导航广告"));
        assertTrue(doc.getQualityScore() >= 0.2);
    }

    @Test
    void fromPaste() {
        MainTextDocument doc = adapter.fromPaste(
                "这是用户粘贴的一段足够长的正文内容，用于验证 paste 清洗路径能够产出可用的主文档对象。",
                "粘贴标题");
        assertFalse(doc.isUnusable());
        assertTrue(doc.getMainText().contains("用户粘贴"));
        assertTrue("paste".equals(doc.getSource()));
    }

    @Test
    void plainTextFetch() {
        String plain = "纯文本正文若干字足够长度用于通过最低质量阈值要求。"
                + "再写一些补充说明与背景信息，确保字符数与字母汉字比例达到可用质量分。"
                + "继续扩展段落内容，避免被误判为广告或过短摘要。";
        ArticleFetchResult raw = ArticleFetchResult.builder()
                .success(true)
                .rawText(plain)
                .contentType("text/plain")
                .build();
        MainTextDocument doc = adapter.extract(raw);
        assertFalse(doc.isUnusable(), "score=" + doc.getQualityScore() + " reason=" + doc.getUnusableReason());
        assertTrue("plain".equals(doc.getSource()));
    }

    @Test
    void emptyUnusable() {
        MainTextDocument doc = adapter.fromPaste("  ", null);
        assertTrue(doc.isUnusable());
    }
}
