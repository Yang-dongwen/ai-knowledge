package com.dwcode.okxbot.imggen.agent.step;

import com.dwcode.okxbot.imggen.enums.ImgGenTaskStatus;

public interface PipelineStep {
    String name();

    ImgGenTaskStatus runningStatus();

    String stepLabel();

    int progressPercent();

    void execute(PipelineContext ctx) throws Exception;
}
