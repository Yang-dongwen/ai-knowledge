package com.dwcode.okxbot.article.config;

import com.dwcode.okxbot.article.adapter.llm.LangchainArticleCoreAdapter;
import com.dwcode.okxbot.article.adapter.llm.LangchainArticleRewriteAdapter;
import com.dwcode.okxbot.article.port.ArticleCoreLlmPort;
import com.dwcode.okxbot.article.port.ArticleRewriteLlmPort;
import com.dwcode.okxbot.article.service.ArticlePromptLoader;
import com.dwcode.okxbot.common.ai.LlmChatClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文章 LLM 端口装配：统一走 {@link LlmChatClient} → LangChain4j（{@code ai.chat-engine=langchain4j}）。
 */
@Slf4j
@Configuration
public class ArticleBeanConfig {

    @Bean
    public ArticleCoreLlmPort articleCoreLlmPort(LlmChatClient llmChatClient,
                                                 ArticleProperties properties,
                                                 ArticlePromptLoader promptLoader,
                                                 ObjectMapper objectMapper) {
        log.info("Article CORE LLM: LangchainArticleCoreAdapter via LlmChatClient/LangChain4j");
        return new LangchainArticleCoreAdapter(llmChatClient, properties, promptLoader, objectMapper);
    }

    @Bean
    public ArticleRewriteLlmPort articleRewriteLlmPort(LlmChatClient llmChatClient,
                                                       ArticleProperties properties,
                                                       ArticlePromptLoader promptLoader,
                                                       ObjectMapper objectMapper) {
        log.info("Article REWRITE LLM: LangchainArticleRewriteAdapter via LlmChatClient/LangChain4j");
        return new LangchainArticleRewriteAdapter(llmChatClient, properties, promptLoader, objectMapper);
    }
}
