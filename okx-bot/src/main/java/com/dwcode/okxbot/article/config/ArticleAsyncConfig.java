package com.dwcode.okxbot.article.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 文章提取专用异步线程池，与 video / aigen / imggen 隔离。
 */
@Configuration
@EnableAsync
@RequiredArgsConstructor
public class ArticleAsyncConfig {

    public static final String ARTICLE_TASK_EXECUTOR = "articleTaskExecutor";

    private final ArticleProperties articleProperties;

    @Bean(name = ARTICLE_TASK_EXECUTOR)
    public Executor articleTaskExecutor() {
        ArticleProperties.Async async = articleProperties.getAsync();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, async.getCorePoolSize()));
        executor.setMaxPoolSize(Math.max(async.getCorePoolSize(), async.getMaxPoolSize()));
        executor.setQueueCapacity(Math.max(1, async.getQueueCapacity()));
        executor.setThreadNamePrefix("article-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
