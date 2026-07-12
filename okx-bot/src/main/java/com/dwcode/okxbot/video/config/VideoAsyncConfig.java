package com.dwcode.okxbot.video.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 视频处理异步线程池。
 * 与交易 Job 隔离，避免长耗时下载/转录阻塞策略调度。
 */
@Configuration
@EnableAsync
public class VideoAsyncConfig {

    public static final String VIDEO_TASK_EXECUTOR = "videoTaskExecutor";

    @Bean(name = VIDEO_TASK_EXECUTOR)
    public Executor videoTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("video-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
