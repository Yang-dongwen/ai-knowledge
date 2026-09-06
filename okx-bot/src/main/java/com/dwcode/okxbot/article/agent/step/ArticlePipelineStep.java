package com.dwcode.okxbot.article.agent.step;

import com.dwcode.okxbot.article.enums.ArticleTaskStatus;

public interface ArticlePipelineStep {

    String name();

    ArticleTaskStatus runningStatus();

    String stepLabel(ArticlePipelineContext ctx);

    int progressPercent();

    boolean applies(ArticlePipelineContext ctx);

    void execute(ArticlePipelineContext ctx) throws Exception;
}
