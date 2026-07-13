package com.dwcode.okxbot.aigen.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI 视频生成专用异步线程池，与交易 Job、视频提取隔离。
 */
@Configuration
public class AigenAsyncConfig {

    public static final String AIGEN_TASK_EXECUTOR = "aigenTaskExecutor";

    @Bean(name = AIGEN_TASK_EXECUTOR)
    public Executor aigenTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("aigen-task-");
        // 队列满时直接拒绝，避免拖垮 HTTP 线程
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
