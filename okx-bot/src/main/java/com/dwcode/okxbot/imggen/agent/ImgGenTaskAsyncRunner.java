package com.dwcode.okxbot.imggen.agent;

import com.dwcode.okxbot.imggen.config.ImgGenAsyncConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImgGenTaskAsyncRunner {

    private final ImgGenPipeline pipeline;

    @Async(ImgGenAsyncConfig.IMGGEN_TASK_EXECUTOR)
    public void runAsync(Long taskId) {
        log.info("异步启动 imggen 任务: taskId={}", taskId);
        pipeline.run(taskId);
    }
}
