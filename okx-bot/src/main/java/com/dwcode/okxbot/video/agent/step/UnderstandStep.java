package com.dwcode.okxbot.video.agent.step;

import com.dwcode.okxbot.common.ai.AiModelConfigService;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.dto.TranscriptionResult;
import com.dwcode.okxbot.video.entity.VideoTaskEntity;
import com.dwcode.okxbot.video.enums.UnderstandingMode;
import com.dwcode.okxbot.video.enums.VideoTaskStatus;
import com.dwcode.okxbot.video.exception.UnderstandingDegradedException;
import com.dwcode.okxbot.video.port.VideoUnderstandingCommand;
import com.dwcode.okxbot.video.port.VisualUnderstandingResult;
import com.dwcode.okxbot.video.service.StorageService;
import com.dwcode.okxbot.video.service.VideoUnderstandingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class UnderstandStep implements VideoPipelineStep {

    private final VideoUnderstandingService videoUnderstandingService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final VideoProperties videoProperties;
    private final AiModelConfigService aiModelConfigService;

    @Override
    public String name() {
        return "understand";
    }

    @Override
    public VideoTaskStatus runningStatus() {
        return VideoTaskStatus.UNDERSTANDING;
    }

    @Override
    public String stepLabel(VideoPipelineContext ctx) {
        return "正在多模态理解画面";
    }

    @Override
    public boolean applies(UnderstandingMode mode) {
        return mode != null && mode.needsOmni();
    }

    @Override
    public void execute(VideoPipelineContext ctx) throws Exception {
        VideoTaskEntity task = ctx.getTask();
        UnderstandingMode mode = ctx.getMode();
        TranscriptionResult transcription = ctx.getTranscription();
        long t0 = System.currentTimeMillis();
        try {
            if (task.getOmniProvider() == null || task.getOmniProvider().isBlank()
                    || task.getOmniModel() == null || task.getOmniModel().isBlank()) {
                throw new IllegalStateException(
                        "任务未指定视频理解模型（omniProvider/omniModel），请重新提交并选择模型");
            }
            String omniProtocol = videoProperties.getUnderstanding().getProtocol();
            try {
                var omniCfg = aiModelConfigService.findEnabledVideoOmniModel(
                        task.getOmniProvider(), task.getOmniModel());
                if (omniCfg != null && omniCfg.getProtocol() != null
                        && !omniCfg.getProtocol().isBlank()) {
                    omniProtocol = omniCfg.getProtocol();
                }
            } catch (Exception ignored) {
                // 沿用 yml protocol
            }
            String videoPath = ctx.getDownload() != null
                    ? ctx.getDownload().getVideoPath() : task.getVideoPath();
            String audioPath = ctx.getDownload() != null
                    ? ctx.getDownload().getAudioPath() : task.getAudioPath();
            VideoUnderstandingCommand cmd = VideoUnderstandingCommand.builder()
                    .taskId(ctx.getTaskIdStr())
                    .videoPath(videoPath)
                    .audioPath(audioPath)
                    .durationSeconds(task.getDurationSeconds())
                    .language(task.getLanguage())
                    .providerKey(task.getOmniProvider())
                    .modelId(task.getOmniModel())
                    .protocol(omniProtocol)
                    .stripAudio(mode == UnderstandingMode.HYBRID
                            && videoProperties.getUnderstanding().isStripAudioOnVisualChunks())
                    .useAudioInVideo(mode == UnderstandingMode.OMNI_ONLY)
                    .priorTranscriptText(transcription != null ? transcription.getText() : null)
                    .build();
            VisualUnderstandingResult visual = videoUnderstandingService.understand(
                    cmd, transcription,
                    () -> ctx.getPauseCheck() != null && ctx.getPauseCheck().getAsBoolean());
            long understandMs = System.currentTimeMillis() - t0;
            task.setUnderstandDurationMs(understandMs);
            task.setVisualJson(objectMapper.writeValueAsString(visual));
            task.setVisualPath(storageService.saveJson(
                    storageService.resolveVisualPath(ctx.getTaskIdStr()), visual));
            ctx.setVisual(visual);
            log.info("步骤耗时: taskId={}, understand={}ms, chunks={}",
                    ctx.getTaskId(), understandMs, visual.getChunkCount());
        } catch (UnderstandingDegradedException de) {
            ctx.setDegraded(true);
            ctx.setDegradeReason(de.getReason());
            task.setDegraded(1);
            task.setDegradeReason(VideoTexts.truncate(de.getReason(), 500));
            task.setUnderstandDurationMs(System.currentTimeMillis() - t0);
            log.warn("视觉理解降级: taskId={}, reason={}", ctx.getTaskId(), de.getReason());
        }
        task.setUpdatedAt(LocalDateTime.now());
    }
}
