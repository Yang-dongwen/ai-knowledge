package com.dwcode.okxbot.imggen.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 文生图专用异步线程池，与交易 Job、视频提取、aigen 隔离。
 */
@Configuration
public class ImgGenAsyncConfig {

    public static final String IMGGEN_TASK_EXECUTOR = "imggenTaskExecutor";

    @Bean(name = IMGGEN_TASK_EXECUTOR)
    public Executor imggenTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("imggen-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
