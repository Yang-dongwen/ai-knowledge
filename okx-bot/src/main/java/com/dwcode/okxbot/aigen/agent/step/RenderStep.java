package com.dwcode.okxbot.aigen.agent.step;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.domain.shot.ShotlistDto;
import com.dwcode.okxbot.aigen.enums.AigenTaskStatus;
import com.dwcode.okxbot.aigen.port.RenderCommand;
import com.dwcode.okxbot.aigen.port.RenderResult;
import com.dwcode.okxbot.aigen.port.VideoRenderPort;
import com.dwcode.okxbot.aigen.service.TemplateRegistry;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
@RequiredArgsConstructor
public class RenderStep implements PipelineStep {

    private final VideoRenderPort videoRenderPort;
    private final TemplateRegistry templateRegistry;
    private final AigenProperties aigenProperties;
    private final ObjectMapper objectMapper;

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
        if (ctx.isVisualMode()) {
            executeVisual(ctx);
        } else {
            executeTemplate(ctx);
        }
    }

    private void executeVisual(PipelineContext ctx) throws Exception {
        ShotlistDto list = ctx.getShotlist();
        if (list == null && ctx.getTask().getStoryboardJson() != null) {
            list = objectMapper.readValue(ctx.getTask().getStoryboardJson(), ShotlistDto.class);
            ctx.setShotlist(list);
        }
        if (list == null) {
            throw new IllegalStateException("shotlist 为空，无法渲染");
        }
        String compositionId = aigenProperties.getVisual().getCompositionId();
        if (compositionId == null || compositionId.isBlank()) {
            compositionId = "VisualTimeline";
        }
        RenderResult result = videoRenderPort.render(RenderCommand.builder()
                .jobId(String.valueOf(ctx.getTaskId()))
                .compositionId(compositionId)
                .inputProps(list)
                .workDir(ctx.getWorkDir())
                .outputFileName("output.mp4")
                .build());
        applyResult(ctx, result);
        if (list.getMeta() != null
                && list.getMeta().getDurationInFrames() != null
                && list.getMeta().getFps() != null
                && list.getMeta().getFps() > 0) {
            ctx.getTask().setDurationSeconds(
                    list.getMeta().getDurationInFrames() / (double) list.getMeta().getFps());
        } else if (ctx.getTask().getTargetDurationSec() != null) {
            ctx.getTask().setDurationSeconds(ctx.getTask().getTargetDurationSec().doubleValue());
        }
    }

    private void executeTemplate(PipelineContext ctx) throws Exception {
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
        applyResult(ctx, result);
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

    private void applyResult(PipelineContext ctx, RenderResult result) throws Exception {
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
    }
}
