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
 * 步骤间检查暂停请求；结束时释放调度槽位并启动排队任务。
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
    private final VideoTaskScheduler taskScheduler;

    public void run(Long taskId) {
        VideoTaskEntity task = videoTaskMapper.selectById(taskId);
        if (task == null) {
            log.error("视频任务不存在: {}", taskId);
            taskScheduler.markFinished(taskId);
            return;
        }

        // 已被暂停/删除等，不再执行
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

        try {
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
            log.info("步骤耗时: taskId={}, download={}ms", taskId, downloadMs);

            if (shouldPause(taskId, task, pipelineStart)) {
                return;
            }

            // Step 2: Transcribe
            updateStatus(task, VideoTaskStatus.TRANSCRIBING, "正在转录音频");
            t0 = System.currentTimeMillis();
            TranscriptionResult transcription = transcriptionService.transcribe(
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
            log.info("步骤耗时: taskId={}, transcribe={}ms", taskId, transcribeMs);

            if (shouldPause(taskId, task, pipelineStart)) {
                return;
            }

            // Step 3: Summarize
            // 重新读库，重试时可能刚改了 llm 字段（一般启动前已写好）
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
            VideoSummaryPart summaryPart = summarizationService.summarize(
                    task.getTitle(), transcription, mindMap, repurpose, task.getLanguage(),
                    task.getLlmProvider(), task.getLlmModel());
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
            response.setSummary(summaryPart);
            response.setTranscription(transcription);

            task.setResultJson(objectMapper.writeValueAsString(response));
            task.setStatus(VideoTaskStatus.SUCCESS.name());
            task.setCurrentStep("完成");
            task.setFinishedAt(LocalDateTime.now());
            task.setTotalDurationMs(System.currentTimeMillis() - pipelineStart);
            task.setUpdatedAt(LocalDateTime.now());
            task.setErrorMessage(null);
            videoTaskMapper.updateById(task);

            if (videoProperties.isCleanupMedia()) {
                cleanupTaskMedia(taskId);
                task.setVideoPath(null);
                task.setAudioPath(null);
                task.setTranscriptionPath(null);
                task.setSummaryPath(null);
                task.setUpdatedAt(LocalDateTime.now());
                videoTaskMapper.updateById(task);
            }

            log.info("视频任务完成: taskId={}, title={}, total={}ms",
                    taskId, task.getTitle(), task.getTotalDurationMs());
        } catch (Exception e) {
            // 若已请求暂停，优先记为暂停而非失败
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
        } finally {
            taskScheduler.markFinished(taskId);
        }
    }

    /**
     * 步骤间隙检查暂停；若需暂停则落库并返回 true。
     */
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
        // 重新加载避免覆盖已写入的步骤耗时
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
        // 同步内存对象
        task.setStatus(latest.getStatus());
        task.setCurrentStep(latest.getCurrentStep());
        task.setTotalDurationMs(latest.getTotalDurationMs());
        log.info("任务已暂停: taskId={}, step={}", task.getId(), step);
    }

    private void updateStatus(VideoTaskEntity task, VideoTaskStatus status, String step) {
        // 暂停请求时不要覆盖为进行中
        if (taskScheduler.isPauseRequested(task.getId())) {
            return;
        }
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
