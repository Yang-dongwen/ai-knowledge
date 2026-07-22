package com.dwcode.okxbot.article.adapter.llm;

import com.dwcode.okxbot.article.config.ArticleProperties;
import com.dwcode.okxbot.article.port.ArticleCoreCommand;
import com.dwcode.okxbot.article.port.ArticleCoreResult;
import com.dwcode.okxbot.article.port.MainTextDocument;
import com.dwcode.okxbot.article.service.ArticlePromptLoader;
import com.dwcode.okxbot.common.ai.LlmCallOptions;
import com.dwcode.okxbot.common.ai.LlmChatClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LangchainArticleCoreAdapterTest {

    @Mock
    private LlmChatClient llmChatClient;

    private LangchainArticleCoreAdapter adapter;

    @BeforeEach
    void setUp() {
        ArticleProperties props = new ArticleProperties();
        props.getLlm().setJsonObjectEnabled(true);
        props.getLlm().setDigestSingleWindowChars(12000);
        // 使用真实 classpath prompts
        adapter = new LangchainArticleCoreAdapter(
                llmChatClient, props, new ArticlePromptLoader(), new ObjectMapper());
    }

    @Test
    void extractCoreViaLlmChatClient() {
        String json = """
                {
                  "title": "测试标题",
                  "summary": "这是一段足够长的客观摘要用于通过校验规则。",
                  "keyPoints": [{"point":"要点一","importance":"high"}],
                  "entities": {"people":[],"orgs":[],"places":[],"products":[]},
                  "timeline": [],
                  "quotes": [],
                  "sentiment": "neutral",
                  "category": "科技",
                  "mindMapMarkdown": null,
                  "sourceFidelityNotes": "",
                  "truncatedInput": false
                }
                """;
        when(llmChatClient.chat(anyString(), anyString(), anyString(), anyString(), any(LlmCallOptions.class)))
                .thenReturn(json);

        MainTextDocument doc = MainTextDocument.builder()
                .mainText("正文内容若干字。" + "补充段落。".repeat(20))
                .title("原始标题")
                .build();
        ArticleCoreResult r = adapter.extractCore(doc, ArticleCoreCommand.builder()
                .language("zh")
                .llmProvider("nvidia")
                .llmModel("test-model")
                .extractMindMap(false)
                .build());

        assertNotNull(r.getRawJson());
        assertEquals("测试标题", r.getTitle());
        assertTrue(r.getSummary().contains("客观摘要"));
    }
}
