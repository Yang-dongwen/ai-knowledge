package com.dwcode.okxbot.article.agent;

import com.dwcode.okxbot.article.config.ArticleAsyncConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleTaskAsyncRunner {

    private final ArticlePipeline pipeline;

    @Async(ArticleAsyncConfig.ARTICLE_TASK_EXECUTOR)
    public void runAsync(Long taskId) {
        log.info("异步启动 article 任务: taskId={}", taskId);
        pipeline.run(taskId);
    }
}
