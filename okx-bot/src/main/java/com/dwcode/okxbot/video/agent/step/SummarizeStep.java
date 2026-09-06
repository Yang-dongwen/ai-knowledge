package com.dwcode.okxbot.video.agent.step;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.dto.TranscriptDigest;
import com.dwcode.okxbot.video.dto.TranscriptionResult;
import com.dwcode.okxbot.video.dto.VideoSummaryPart;
import com.dwcode.okxbot.video.dto.VideoSummaryResponse;
import com.dwcode.okxbot.video.entity.VideoTaskEntity;
import com.dwcode.okxbot.video.enums.UnderstandingMode;
import com.dwcode.okxbot.video.enums.VideoTaskStatus;
import com.dwcode.okxbot.video.mapper.VideoTaskMapper;
import com.dwcode.okxbot.video.port.VisualUnderstandingResult;
import com.dwcode.okxbot.video.service.StorageService;
import com.dwcode.okxbot.video.service.SummarizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SummarizeStep implements VideoPipelineStep {

    private final SummarizationService summarizationService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final VideoTaskMapper videoTaskMapper;

    @Override
    public String name() {
        return "summarize";
    }

    @Override
    public VideoTaskStatus runningStatus() {
        return VideoTaskStatus.SUMMARIZING;
    }

    @Override
    public String stepLabel(VideoPipelineContext ctx) {
        VideoTaskEntity task = ctx.getTask();
        String model = task != null ? task.getLlmModel() : null;
        return "正在生成结构化摘要" + (model != null ? "（" + model + "）" : "");
    }

    @Override
    public boolean applies(UnderstandingMode mode) {
        return mode != null && mode.needsLlm();
    }

    @Override
    public void prepare(VideoPipelineContext ctx) {
        VideoTaskEntity task = ctx.getTask();
        VideoTaskEntity latest = videoTaskMapper.selectById(ctx.getTaskId());
        if (latest != null && task != null) {
            task.setLlmProvider(latest.getLlmProvider());
            task.setLlmModel(latest.getLlmModel());
        }
    }

    @Override
    public void execute(VideoPipelineContext ctx) throws Exception {
        VideoTaskEntity task = ctx.getTask();
        UnderstandingMode mode = ctx.getMode();

        boolean mindMap = task.getExtractMindMap() == null || task.getExtractMindMap() == 1;
        boolean repurpose = task.getGenerateRepurposeScript() == null || task.getGenerateRepurposeScript() == 1;
        TranscriptionResult transcription = ctx.getTranscription();
        VisualUnderstandingResult visual = ctx.getVisual();
        boolean degraded = ctx.isDegraded();
        String degradeReason = ctx.getDegradeReason();

        long t0 = System.currentTimeMillis();
        TranscriptDigest digest = null;
        if (transcription != null) {
            digest = summarizationService.prepareTranscriptDigest(
                    transcription, task.getLanguage(), task.getLlmProvider(), task.getLlmModel());
        }

        VideoSummaryPart summaryPart;
        if (visual != null && !degraded) {
            summaryPart = summarizationService.summarizeFused(
                    task.getTitle(), digest, visual, mindMap, repurpose, task.getLanguage(),
                    task.getLlmProvider(), task.getLlmModel());
        } else if (digest != null) {
            summaryPart = summarizationService.summarizeFromDigest(
                    task.getTitle(), digest, mindMap, repurpose, task.getLanguage(),
                    task.getLlmProvider(), task.getLlmModel());
        } else if (visual != null) {
            summaryPart = summarizationService.summarizeFused(
                    task.getTitle(), null, visual, mindMap, repurpose, task.getLanguage(),
                    task.getLlmProvider(), task.getLlmModel());
        } else {
            throw new BusinessException("无转录且无视觉理解结果，无法总结");
        }

        summaryPart.setUnderstandingMode(mode.wireValue());
        summaryPart.setDegraded(degraded);
        summaryPart.setDegradeReason(degradeReason);
        if (visual != null) {
            summaryPart.setMultimodal(true);
            summaryPart.setPartialVisual(visual.isPartial());
        }

        long summarizeMs = System.currentTimeMillis() - t0;
        task.setSummarizeDurationMs(summarizeMs);
        log.info("步骤耗时: taskId={}, summarize={}ms", ctx.getTaskId(), summarizeMs);

        task.setSummaryJson(objectMapper.writeValueAsString(summaryPart));
        task.setSummaryPath(storageService.saveJson(
                storageService.resolveSummaryPath(ctx.getTaskIdStr()), summaryPart));

        VideoSummaryResponse response = new VideoSummaryResponse();
        response.setVideoId(ctx.getTaskIdStr());
        response.setTitle(task.getTitle());
        response.setDuration(task.getDurationSeconds());
        response.setSourceUrl(task.getSourceUrl());
        response.setUnderstandingMode(mode.wireValue());
        response.setDegraded(degraded);
        response.setDegradeReason(degradeReason);
        response.setSummary(summaryPart);
        response.setTranscription(transcription);

        task.setResultJson(objectMapper.writeValueAsString(response));
        if (degraded) {
            task.setDegraded(1);
            task.setDegradeReason(VideoTexts.truncate(degradeReason, 500));
        } else {
            task.setDegraded(0);
        }
    }
}
