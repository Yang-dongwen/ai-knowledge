package com.dwcode.okxbot.article.port;

/**
 * 文章核心提取（经 LangChain4j / {@code LlmChatClient}）。
 */
public interface ArticleCoreLlmPort {

    ArticleCoreResult extractCore(MainTextDocument doc, ArticleCoreCommand cmd);
}
