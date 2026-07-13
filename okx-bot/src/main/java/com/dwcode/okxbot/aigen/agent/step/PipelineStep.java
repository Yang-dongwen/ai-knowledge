package com.dwcode.okxbot.aigen.agent.step;

import com.dwcode.okxbot.aigen.enums.AigenTaskStatus;

public interface PipelineStep {
    String name();

    AigenTaskStatus runningStatus();

    String stepLabel();

    int progressPercent();

    void execute(PipelineContext ctx) throws Exception;
}
