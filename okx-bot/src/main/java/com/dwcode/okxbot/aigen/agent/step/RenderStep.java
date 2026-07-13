package com.dwcode.okxbot.aigen.agent.step;

import com.dwcode.okxbot.aigen.enums.AigenTaskStatus;
import com.dwcode.okxbot.aigen.port.RenderCommand;
import com.dwcode.okxbot.aigen.port.RenderResult;
import com.dwcode.okxbot.aigen.port.VideoRenderPort;
import com.dwcode.okxbot.aigen.service.TemplateRegistry;
import com.dwcode.okxbot.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
@RequiredArgsConstructor
public class RenderStep implements PipelineStep {

    private final VideoRenderPort videoRenderPort;
    private final TemplateRegistry templateRegistry;

    @Override
    public String name() {
        return "render";
    }

    @Override
    public AigenTaskStatus runningStatus() {
        return AigenTaskStatus.RENDERING;
    }

    @Override
    public String stepLabel() {
        return "正在渲染视频";
    }

    @Override
    public int progressPercent() {
        return 80;
    }

    @Override
    public void execute(PipelineContext ctx) throws Exception {
        if (ctx.getStoryboard() == null) {
            throw new IllegalStateException("storyboard 为空，无法渲染");
        }
        var def = templateRegistry.require(ctx.getTask().getTemplateId());
        RenderResult result = videoRenderPort.render(RenderCommand.builder()
                .jobId(String.valueOf(ctx.getTaskId()))
                .compositionId(def.compositionId())
                .storyboard(ctx.getStoryboard())
                .workDir(ctx.getWorkDir())
                .outputFileName("output.mp4")
                .build());

        if (!result.isSuccess()) {
            throw new BusinessException(result.getError() != null ? result.getError() : "渲染失败");
        }

        if (result.getOutputAbsolutePath() != null) {
            Path out = Path.of(result.getOutputAbsolutePath());
            if (Files.isRegularFile(out)) {
                ctx.getTask().setOutputPath(out.toAbsolutePath().toString());
                ctx.getTask().setOutputSizeBytes(Files.size(out));
            }
        }
        if (ctx.getStoryboard().getMeta() != null
                && ctx.getStoryboard().getMeta().getDurationInFrames() != null
                && ctx.getStoryboard().getMeta().getFps() != null
                && ctx.getStoryboard().getMeta().getFps() > 0) {
            double sec = ctx.getStoryboard().getMeta().getDurationInFrames()
                    / (double) ctx.getStoryboard().getMeta().getFps();
            ctx.getTask().setDurationSeconds(sec);
        } else if (ctx.getTask().getTargetDurationSec() != null) {
            ctx.getTask().setDurationSeconds(ctx.getTask().getTargetDurationSec().doubleValue());
        }
    }
}
