package com.dwcode.okxbot.aigen.agent.step;

import com.dwcode.okxbot.aigen.domain.StoryboardDto;
import com.dwcode.okxbot.aigen.entity.AigenTaskEntity;
import lombok.Data;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

@Data
public class PipelineContext {
    private Long taskId;
    private AigenTaskEntity task;
    private Path workDir;
    private StoryboardDto storyboard;
    private BooleanSupplier cancelCheck;
    private long pipelineStartMs;
}
