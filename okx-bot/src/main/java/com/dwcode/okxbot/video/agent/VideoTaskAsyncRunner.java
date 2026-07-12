package com.dwcode.okxbot.video.agent;

import com.dwcode.okxbot.video.config.VideoAsyncConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 异步任务触发器。
 *
 * 独立 Bean，确保 {@code @Async} 经 Spring 代理生效（避免同类自调用失效）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoTaskAsyncRunner {

    private final VideoProcessingPipeline pipeline;

    @Async(VideoAsyncConfig.VIDEO_TASK_EXECUTOR)
    public void runAsync(Long taskId) {
        log.info("异步启动视频任务: taskId={}", taskId);
        pipeline.run(taskId);
    }
}
