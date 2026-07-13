package com.dwcode.okxbot.imggen.agent.step;

import com.dwcode.okxbot.imggen.entity.ImgGenTaskEntity;
import com.dwcode.okxbot.imggen.port.ImageAsset;
import lombok.Data;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

@Data
public class PipelineContext {
    private Long taskId;
    private ImgGenTaskEntity task;
    private Path workDir;
    private long pipelineStartMs;
    private BooleanSupplier cancelCheck;
    private String finalPrompt;
    private List<ImageAsset> images;
}
