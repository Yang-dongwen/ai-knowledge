package com.dwcode.okxbot.video.agent;

import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.dto.TranscriptionResult;
import com.dwcode.okxbot.video.dto.VideoSummaryPart;
import com.dwcode.okxbot.video.dto.VideoSummaryResponse;
import com.dwcode.okxbot.video.entity.VideoTaskEntity;
import com.dwcode.okxbot.video.enums.VideoTaskStatus;
import com.dwcode.okxbot.video.mapper.VideoTaskMapper;
import com.dwcode.okxbot.video.service.StorageService;
import com.dwcode.okxbot.video.service.SummarizationService;
import com.dwcode.okxbot.video.service.TranscriptionService;
import com.dwcode.okxbot.video.service.VideoDownloadService;
import com.dwcode.okxbot.video.service.VideoDownloadService.DownloadResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 视频处理流水线（Agent 编排层）。
 *
 * 固定顺序：Download → Transcribe → Summarize。
 * 转录与核心内容同时写入数据库 + 文件系统（v2 持久化）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoProcessingPipeline {

    private final VideoDownloadService downloadService;
    private final TranscriptionService transcriptionService;
    private final SummarizationService summarizationService;
    private final StorageService storageService;
    private final VideoTaskMapper videoTaskMapper;
    private final ObjectMapper objectMapper;
    private final VideoProperties videoProperties;

    /**
     * 执行完整流水线并更新任务状态。
     */
    public void run(Long taskId) {
        VideoTaskEntity task = videoTaskMapper.selectById(taskId);
        if (task == null) {
            log.error("视频任务不存在: {}", taskId);
            return;
        }

        String taskIdStr = String.valueOf(taskId);
        task.setStartedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        videoTaskMapper.updateById(task);

        try {
            // Step 1: Download
            updateStatus(task, VideoTaskStatus.DOWNLOADING, "正在下载视频并提取音频");
            DownloadResult download = downloadService.download(task.getSourceUrl(), taskIdStr);
            task.setTitle(download.getTitle());
            task.setDurationSeconds(download.getDurationSeconds());
            task.setVideoPath(download.getVideoPath());
            task.setAudioPath(download.getAudioPath());
            task.setUpdatedAt(LocalDateTime.now());
            videoTaskMapper.updateById(task);

            // Step 2: Transcribe + 持久化
            updateStatus(task, VideoTaskStatus.TRANSCRIBING, "正在转录音频");
            TranscriptionResult transcription = transcriptionService.transcribe(
                    download.getAudioPath(), task.getLanguage());
            if (transcription.getDurationSeconds() != null) {
                task.setDurationSeconds(transcription.getDurationSeconds());
            }
            String transcriptionJson = objectMapper.writeValueAsString(transcription);
            task.setTranscriptionJson(transcriptionJson);
            String transcriptionPath = storageService.saveJson(
                    storageService.resolveTranscriptionPath(taskIdStr), transcription);
            task.setTranscriptionPath(transcriptionPath);
            task.setUpdatedAt(LocalDateTime.now());
            videoTaskMapper.updateById(task);

            // Step 3: Summarize + 持久化
            updateStatus(task, VideoTaskStatus.SUMMARIZING,
                    "正在生成结构化摘要" + (task.getLlmModel() != null ? "（" + task.getLlmModel() + "）" : ""));
            boolean mindMap = task.getExtractMindMap() == null || task.getExtractMindMap() == 1;
            boolean repurpose = task.getGenerateRepurposeScript() == null || task.getGenerateRepurposeScript() == 1;
            VideoSummaryPart summaryPart = summarizationService.summarize(
                    task.getTitle(), transcription, mindMap, repurpose, task.getLanguage(),
                    task.getLlmProvider(), task.getLlmModel());

            String summaryJson = objectMapper.writeValueAsString(summaryPart);
            task.setSummaryJson(summaryJson);
            String summaryPath = storageService.saveJson(
                    storageService.resolveSummaryPath(taskIdStr), summaryPart);
            task.setSummaryPath(summaryPath);

            VideoSummaryResponse response = new VideoSummaryResponse();
            response.setVideoId(taskIdStr);
            response.setTitle(task.getTitle());
            response.setDuration(task.getDurationSeconds());
            response.setSourceUrl(task.getSourceUrl());
            response.setSummary(summaryPart);
            response.setTranscription(transcription);

            task.setResultJson(objectMapper.writeValueAsString(response));
            task.setStatus(VideoTaskStatus.SUCCESS.name());
            task.setCurrentStep("完成");
            task.setFinishedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            task.setErrorMessage(null);
            videoTaskMapper.updateById(task);

            if (videoProperties.isCleanupMedia()) {
                cleanupTaskMedia(taskId);
                // 清理后清空文件路径，避免接口误导
                task.setVideoPath(null);
                task.setAudioPath(null);
                task.setTranscriptionPath(null);
                task.setSummaryPath(null);
                task.setUpdatedAt(LocalDateTime.now());
                videoTaskMapper.updateById(task);
            }

            log.info("视频任务完成: taskId={}, title={}", taskId, task.getTitle());
        } catch (Exception e) {
            log.error("视频任务失败: taskId={}", taskId, e);
            task.setStatus(VideoTaskStatus.FAILED.name());
            task.setCurrentStep("失败");
            task.setErrorMessage(truncate(e.getMessage(), 1000));
            task.setFinishedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            videoTaskMapper.updateById(task);
        }
    }

    private void updateStatus(VideoTaskEntity task, VideoTaskStatus status, String step) {
        task.setStatus(status.name());
        task.setCurrentStep(step);
        task.setUpdatedAt(LocalDateTime.now());
        videoTaskMapper.updateById(task);
        log.info("任务状态更新: taskId={}, status={}, step={}", task.getId(), status, step);
    }

    private void cleanupTaskMedia(Long taskId) {
        Path dir = storageService.resolveTaskDir(String.valueOf(taskId));
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
            log.info("已清理任务媒体目录: {}", dir);
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
