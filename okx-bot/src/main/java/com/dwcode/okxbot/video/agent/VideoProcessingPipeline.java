package com.dwcode.okxbot.video.agent;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.dto.TranscriptDigest;
import com.dwcode.okxbot.video.dto.TranscriptionResult;
import com.dwcode.okxbot.video.dto.VideoSummaryPart;
import com.dwcode.okxbot.video.dto.VideoSummaryResponse;
import com.dwcode.okxbot.video.entity.VideoTaskEntity;
import com.dwcode.okxbot.video.enums.UnderstandingMode;
import com.dwcode.okxbot.video.enums.VideoTaskStatus;
import com.dwcode.okxbot.video.event.VideoTaskEventPublisher;
import com.dwcode.okxbot.video.exception.UnderstandingDegradedException;
import com.dwcode.okxbot.video.mapper.VideoTaskMapper;
import com.dwcode.okxbot.video.port.VideoUnderstandingCommand;
import com.dwcode.okxbot.video.port.VisualUnderstandingResult;
import com.dwcode.okxbot.video.service.AiModelConfigService;
import com.dwcode.okxbot.video.service.StorageService;
import com.dwcode.okxbot.video.service.SummarizationService;
import com.dwcode.okxbot.video.service.TranscriptionService;
import com.dwcode.okxbot.video.service.VideoDownloadService;
import com.dwcode.okxbot.video.service.VideoDownloadService.DownloadResult;
import com.dwcode.okxbot.video.service.VideoUnderstandingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 视频处理流水线：
 * audio_only: Download → Transcribe → Summarize
 * hybrid:     Download → Transcribe → Understanding → Fuse
 * omni_only:  Download → Understanding → Structure Summarize
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoProcessingPipeline {

    private final VideoDownloadService downloadService;
    private final TranscriptionService transcriptionService;
    private final SummarizationService summarizationService;
    private final VideoUnderstandingService videoUnderstandingService;
    private final StorageService storageService;
    private final VideoTaskMapper videoTaskMapper;
    private final ObjectMapper objectMapper;
    private final VideoProperties videoProperties;
    private final AiModelConfigService aiModelConfigService;
    private final VideoTaskScheduler taskScheduler;
    private final VideoTaskEventPublisher eventPublisher;

    public void run(Long taskId) {
        VideoTaskEntity task = videoTaskMapper.selectById(taskId);
        if (task == null) {
            log.error("视频任务不存在: {}", taskId);
            taskScheduler.markFinished(taskId);
            return;
        }

        if (VideoTaskStatus.PAUSED.name().equals(task.getStatus())
                || VideoTaskStatus.SUCCESS.name().equals(task.getStatus())) {
            log.info("任务状态无需执行: taskId={}, status={}", taskId, task.getStatus());
            taskScheduler.markFinished(taskId);
            return;
        }

        taskScheduler.markRunning(taskId);
        taskScheduler.clearPauseRequest(taskId);

        String taskIdStr = String.valueOf(taskId);
        long pipelineStart = System.currentTimeMillis();
        task.setStartedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        videoTaskMapper.updateById(task);
        eventPublisher.publishEntity(task, VideoTaskEventPublisher.TYPE_STATUS);

        try {
            UnderstandingMode mode = UnderstandingMode.from(
                    task.getUnderstandingMode() != null
                            ? task.getUnderstandingMode()
                            : videoProperties.getUnderstanding().getMode());

            // Step 1: Download
            if (shouldPause(taskId, task, pipelineStart)) {
                return;
            }
            updateStatus(task, VideoTaskStatus.DOWNLOADING, "正在下载视频并提取音频");
            long t0 = System.currentTimeMillis();
            DownloadResult download = downloadService.download(task.getSourceUrl(), taskIdStr);
            long downloadMs = System.currentTimeMillis() - t0;
            task.setDownloadDurationMs(downloadMs);
            task.setTitle(download.getTitle());
            task.setDurationSeconds(download.getDurationSeconds());
            task.setVideoPath(download.getVideoPath());
            task.setAudioPath(download.getAudioPath());
            task.setUpdatedAt(LocalDateTime.now());
            videoTaskMapper.updateById(task);
            eventPublisher.publishEntity(task, VideoTaskEventPublisher.TYPE_STATUS);
            log.info("步骤耗时: taskId={}, download={}ms", taskId, downloadMs);

            mode = enforceOmniDurationLimit(task, mode, download.getDurationSeconds());

            if (shouldPause(taskId, task, pipelineStart)) {
                return;
            }

            // Step 2: Transcribe (optional)
            TranscriptionResult transcription = null;
            if (mode.needsWhisper()) {
                updateStatus(task, VideoTaskStatus.TRANSCRIBING, "正在转录音频");
                t0 = System.currentTimeMillis();
                transcription = transcriptionService.transcribe(
                        download.getAudioPath(), task.getLanguage());
                long transcribeMs = System.currentTimeMillis() - t0;
                task.setTranscribeDurationMs(transcribeMs);
                if (transcription.getDurationSeconds() != null) {
                    task.setDurationSeconds(transcription.getDurationSeconds());
                }
                task.setTranscriptionJson(objectMapper.writeValueAsString(transcription));
                task.setTranscriptionPath(storageService.saveJson(
                        storageService.resolveTranscriptionPath(taskIdStr), transcription));
                task.setUpdatedAt(LocalDateTime.now());
                videoTaskMapper.updateById(task);
                eventPublisher.publishEntity(task, VideoTaskEventPublisher.TYPE_STATUS);
                log.info("步骤耗时: taskId={}, transcribe={}ms", taskId, transcribeMs);

                if (shouldPause(taskId, task, pipelineStart)) {
                    return;
                }
            }

            // Step 3: Understanding (optional)
            VisualUnderstandingResult visual = null;
            boolean degraded = false;
            String degradeReason = null;
            if (mode.needsOmni()) {
                updateStatus(task, VideoTaskStatus.UNDERSTANDING, "正在多模态理解画面");
                t0 = System.currentTimeMillis();
                try {
                    if (task.getOmniProvider() == null || task.getOmniProvider().isBlank()
                            || task.getOmniModel() == null || task.getOmniModel().isBlank()) {
                        throw new IllegalStateException(
                                "任务未指定视频理解模型（omniProvider/omniModel），请重新提交并选择模型");
                    }
                    // 协议优先用库表模型配置，其次 yml 默认（非模型 ID）
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
                    VideoUnderstandingCommand cmd = VideoUnderstandingCommand.builder()
                            .taskId(taskIdStr)
                            .videoPath(download.getVideoPath())
                            .audioPath(download.getAudioPath())
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
                    visual = videoUnderstandingService.understand(
                            cmd, transcription, () -> taskScheduler.isPauseRequested(taskId));
                    long understandMs = System.currentTimeMillis() - t0;
                    task.setUnderstandDurationMs(understandMs);
                    task.setVisualJson(objectMapper.writeValueAsString(visual));
                    task.setVisualPath(storageService.saveJson(
                            storageService.resolveVisualPath(taskIdStr), visual));
                    log.info("步骤耗时: taskId={}, understand={}ms, chunks={}",
                            taskId, understandMs, visual.getChunkCount());
                } catch (UnderstandingDegradedException de) {
                    degraded = true;
                    degradeReason = de.getReason();
                    task.setDegraded(1);
                    task.setDegradeReason(truncate(degradeReason, 500));
                    task.setUnderstandDurationMs(System.currentTimeMillis() - t0);
                    log.warn("视觉理解降级: taskId={}, reason={}", taskId, degradeReason);
                }
                task.setUpdatedAt(LocalDateTime.now());
                videoTaskMapper.updateById(task);
                eventPublisher.publishEntity(task, VideoTaskEventPublisher.TYPE_STATUS);

                if (shouldPause(taskId, task, pipelineStart)) {
                    return;
                }
            }

            // Step 4: Summarize / Fuse
            VideoTaskEntity latest = videoTaskMapper.selectById(taskId);
            if (latest != null) {
                task.setLlmProvider(latest.getLlmProvider());
                task.setLlmModel(latest.getLlmModel());
            }

            updateStatus(task, VideoTaskStatus.SUMMARIZING,
                    "正在生成结构化摘要" + (task.getLlmModel() != null ? "（" + task.getLlmModel() + "）" : ""));
            t0 = System.currentTimeMillis();
            boolean mindMap = task.getExtractMindMap() == null || task.getExtractMindMap() == 1;
            boolean repurpose = task.getGenerateRepurposeScript() == null || task.getGenerateRepurposeScript() == 1;

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
            log.info("步骤耗时: taskId={}, summarize={}ms", taskId, summarizeMs);

            if (shouldPause(taskId, task, pipelineStart)) {
                return;
            }

            task.setSummaryJson(objectMapper.writeValueAsString(summaryPart));
            task.setSummaryPath(storageService.saveJson(
                    storageService.resolveSummaryPath(taskIdStr), summaryPart));

            VideoSummaryResponse response = new VideoSummaryResponse();
            response.setVideoId(taskIdStr);
            response.setTitle(task.getTitle());
            response.setDuration(task.getDurationSeconds());
            response.setSourceUrl(task.getSourceUrl());
            response.setUnderstandingMode(mode.wireValue());
            response.setDegraded(degraded);
            response.setDegradeReason(degradeReason);
            response.setSummary(summaryPart);
            response.setTranscription(transcription);

            task.setResultJson(objectMapper.writeValueAsString(response));
            task.setStatus(VideoTaskStatus.SUCCESS.name());
            task.setCurrentStep(degraded ? "完成（已降级为纯音频总结）" : "完成");
            task.setFinishedAt(LocalDateTime.now());
            task.setTotalDurationMs(System.currentTimeMillis() - pipelineStart);
            task.setUpdatedAt(LocalDateTime.now());
            task.setErrorMessage(null);
            if (degraded) {
                task.setDegraded(1);
                task.setDegradeReason(truncate(degradeReason, 500));
            } else {
                task.setDegraded(0);
            }
            videoTaskMapper.updateById(task);
            eventPublisher.publishEntity(task, VideoTaskEventPublisher.TYPE_STATUS);

            if (videoProperties.isCleanupMedia()) {
                // 仅清理媒体，保留 json
                cleanupMediaOnly(taskIdStr);
                task.setVideoPath(null);
                task.setAudioPath(null);
                task.setUpdatedAt(LocalDateTime.now());
                videoTaskMapper.updateById(task);
                eventPublisher.publishEntity(task, VideoTaskEventPublisher.TYPE_STATUS);
            }

            log.info("视频任务完成: taskId={}, title={}, mode={}, degraded={}, total={}ms",
                    taskId, task.getTitle(), mode.wireValue(), degraded, task.getTotalDurationMs());
        } catch (Exception e) {
            if (taskScheduler.isPauseRequested(taskId) || isPausedInDb(taskId)) {
                markPaused(task, pipelineStart, "用户暂停（当前步骤被中断）");
                return;
            }
            log.error("视频任务失败: taskId={}", taskId, e);
            task.setStatus(VideoTaskStatus.FAILED.name());
            task.setCurrentStep("失败");
            task.setErrorMessage(truncate(e.getMessage(), 1000));
            task.setFinishedAt(LocalDateTime.now());
            task.setTotalDurationMs(System.currentTimeMillis() - pipelineStart);
            task.setUpdatedAt(LocalDateTime.now());
            videoTaskMapper.updateById(task);
            eventPublisher.publishEntity(task, VideoTaskEventPublisher.TYPE_STATUS);
        } finally {
            taskScheduler.markFinished(taskId);
        }
    }

    private UnderstandingMode enforceOmniDurationLimit(VideoTaskEntity task, UnderstandingMode mode, Double durationSec) {
        if (!mode.needsOmni() || durationSec == null) {
            return mode;
        }
        VideoProperties.Understanding u = videoProperties.getUnderstanding();
        int max = u.getOmniMaxDurationSeconds() > 0
                ? u.getOmniMaxDurationSeconds()
                : u.getHybridMaxDurationSeconds();
        if (max <= 0) {
            return mode;
        }
        if (durationSec <= max) {
            return mode;
        }
        String action = u.getOnOmniTooLong() != null
                ? u.getOnOmniTooLong().toLowerCase(Locale.ROOT) : "reject";
        if ("force_audio".equals(action) && mode == UnderstandingMode.HYBRID) {
            log.warn("视频超 Omni 软顶，强制 audio_only: taskId={}, duration={}, max={}",
                    task.getId(), durationSec, max);
            task.setUnderstandingMode(UnderstandingMode.AUDIO_ONLY.wireValue());
            task.setUpdatedAt(LocalDateTime.now());
            videoTaskMapper.updateById(task);
            return UnderstandingMode.AUDIO_ONLY;
        }
        throw new BusinessException("视频时长 " + durationSec.intValue()
                + "s 超过多模态上限 " + max + "s，请缩短视频或改用 audio_only");
    }

    private boolean shouldPause(Long taskId, VideoTaskEntity task, long pipelineStart) {
        if (!taskScheduler.isPauseRequested(taskId) && !isPausedInDb(taskId)) {
            return false;
        }
        markPaused(task, pipelineStart, "用户已暂停，等待重新调度");
        return true;
    }

    private boolean isPausedInDb(Long taskId) {
        VideoTaskEntity latest = videoTaskMapper.selectById(taskId);
        return latest != null && VideoTaskStatus.PAUSED.name().equals(latest.getStatus());
    }

    private void markPaused(VideoTaskEntity task, long pipelineStart, String step) {
        VideoTaskEntity latest = videoTaskMapper.selectById(task.getId());
        if (latest == null) {
            return;
        }
        if (VideoTaskStatus.SUCCESS.name().equals(latest.getStatus())
                || VideoTaskStatus.FAILED.name().equals(latest.getStatus())) {
            return;
        }
        latest.setStatus(VideoTaskStatus.PAUSED.name());
        latest.setCurrentStep(step);
        latest.setErrorMessage(null);
        latest.setFinishedAt(LocalDateTime.now());
        latest.setTotalDurationMs(System.currentTimeMillis() - pipelineStart);
        latest.setUpdatedAt(LocalDateTime.now());
        videoTaskMapper.updateById(latest);
        task.setStatus(latest.getStatus());
        task.setCurrentStep(latest.getCurrentStep());
        task.setTotalDurationMs(latest.getTotalDurationMs());
        eventPublisher.publishEntity(latest, VideoTaskEventPublisher.TYPE_STATUS);
        log.info("任务已暂停: taskId={}, step={}", task.getId(), step);
    }

    private void updateStatus(VideoTaskEntity task, VideoTaskStatus status, String step) {
        if (taskScheduler.isPauseRequested(task.getId())) {
            return;
        }
        task.setStatus(status.name());
        task.setCurrentStep(step);
        task.setUpdatedAt(LocalDateTime.now());
        videoTaskMapper.updateById(task);
        eventPublisher.publishEntity(task, VideoTaskEventPublisher.TYPE_STATUS);
        log.info("任务状态更新: taskId={}, status={}, step={}", task.getId(), status, step);
    }

    /** 仅删除媒体与分片，保留 json */
    private void cleanupMediaOnly(String taskIdStr) {
        Path dir = storageService.resolveTaskDir(taskIdStr);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.list(dir)) {
            walk.forEach(p -> {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".json")) {
                    return;
                }
                try {
                    if (Files.isDirectory(p)) {
                        try (Stream<Path> nested = Files.walk(p)) {
                            nested.sorted(Comparator.reverseOrder()).forEach(n -> {
                                try {
                                    Files.deleteIfExists(n);
                                } catch (IOException ignored) {
                                    // ignore
                                }
                            });
                        }
                    } else {
                        Files.deleteIfExists(p);
                    }
                } catch (IOException e) {
                    log.debug("清理媒体忽略: {}", e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("清理任务媒体失败: {}", e.getMessage());
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
