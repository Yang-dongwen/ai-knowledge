package com.dwcode.okxbot.aigen.agent;

import com.dwcode.okxbot.aigen.config.AigenAsyncConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 异步触发器（独立 Bean，保证 @Async 代理生效）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AigenTaskAsyncRunner {

    private final AigenPipeline pipeline;

    @Async(AigenAsyncConfig.AIGEN_TASK_EXECUTOR)
    public void runAsync(Long taskId) {
        log.info("异步启动 aigen 任务: taskId={}", taskId);
        pipeline.run(taskId);
    }
}
