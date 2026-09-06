package com.dwcode.okxbot.video.agent.step;

import com.dwcode.okxbot.video.enums.UnderstandingMode;
import com.dwcode.okxbot.video.enums.VideoTaskStatus;

public interface VideoPipelineStep {

    String name();

    VideoTaskStatus runningStatus();

    String stepLabel(VideoPipelineContext ctx);

    boolean applies(UnderstandingMode mode);

    /** 更新状态文案前调用（例如刷新任务上的模型字段）。 */
    default void prepare(VideoPipelineContext ctx) {
    }

    void execute(VideoPipelineContext ctx) throws Exception;
}
