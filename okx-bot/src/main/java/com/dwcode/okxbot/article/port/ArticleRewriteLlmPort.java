package com.dwcode.okxbot.article.port;

/**
 * 多形态二次创作（经 LangChain4j / {@code LlmChatClient}）。
 */
public interface ArticleRewriteLlmPort {

    ArticleRewriteResult rewrite(ArticleCoreResult core, MainTextDocument doc, ArticleRewriteCommand cmd);
}
