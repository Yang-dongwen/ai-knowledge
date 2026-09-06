package com.dwcode.okxbot.video.agent;

import com.dwcode.okxbot.video.agent.step.DownloadStep;
import com.dwcode.okxbot.video.agent.step.SummarizeStep;
import com.dwcode.okxbot.video.agent.step.TranscribeStep;
import com.dwcode.okxbot.video.agent.step.UnderstandStep;
import com.dwcode.okxbot.video.agent.step.VideoPipelineContext;
import com.dwcode.okxbot.video.agent.step.VideoPipelineStep;
import com.dwcode.okxbot.video.agent.step.VideoTexts;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.dto.VideoSummaryResponse;
import com.dwcode.okxbot.video.entity.VideoTaskEntity;
import com.dwcode.okxbot.video.enums.UnderstandingMode;
import com.dwcode.okxbot.video.enums.VideoTaskStatus;
import com.dwcode.okxbot.video.event.VideoTaskEventPublisher;
import com.dwcode.okxbot.video.mapper.VideoTaskMapper;
import com.dwcode.okxbot.video.service.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 视频处理流水线外壳：暂停边界、落库、SSE。
 * 步骤由 {@link UnderstandingMode#stepsAfterDownload()} 选择。
 */
@Slf4j
@Component
public class VideoProcessingPipeline {

    private final StorageService storageService;
    private final VideoTaskMapper videoTaskMapper;
    private final ObjectMapper objectMapper;
    private final VideoProperties videoProperties;
    private final VideoTaskScheduler taskScheduler;
    private final VideoTaskEventPublisher eventPublisher;
    private final DownloadStep downloadStep;
    private final List<VideoPipelineStep> afterDownloadSteps;

    public VideoProcessingPipeline(StorageService storageService,
                                   VideoTaskMapper videoTaskMapper,
                                   ObjectMapper objectMapper,
                                   VideoProperties videoProperties,
                                   VideoTaskScheduler taskScheduler,
                                   VideoTaskEventPublisher eventPublisher,
                                   DownloadStep downloadStep,
                                   TranscribeStep transcribeStep,
                                   UnderstandStep understandStep,
                                   SummarizeStep summarizeStep) {
        this.storageService = storageService;
        this.videoTaskMapper = videoTaskMapper;
        this.objectMapper = objectMapper;
        this.videoProperties = videoProperties;
        this.taskScheduler = taskScheduler;
        this.eventPublisher = eventPublisher;
        this.downloadStep = downloadStep;
        this.afterDownloadSteps = List.of(transcribeStep, understandStep, summarizeStep);
    }

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

        VideoPipelineContext ctx = new VideoPipelineContext();
        ctx.setTaskId(taskId);
        ctx.setTaskIdStr(taskIdStr);
        ctx.setTask(task);
        ctx.setPipelineStartMs(pipelineStart);
        ctx.setPauseCheck(() -> taskScheduler.isPauseRequested(taskId));
        ctx.setMode(UnderstandingMode.from(
                task.getUnderstandingMode() != null
                        ? task.getUnderstandingMode()
                        : videoProperties.getUnderstanding().getMode()));

        try {
            if (shouldPause(taskId, task, pipelineStart)) {
                return;
            }
            runStep(downloadStep, ctx);
            persistStep(ctx);
            if (shouldPause(taskId, ctx.getTask(), pipelineStart)) {
                return;
            }

            if (ctx.getMode().isDownloadOnly()) {
                finishDownloadOnly(ctx.getTask(), taskIdStr, pipelineStart);
                return;
            }

            UnderstandingMode next = OmniDurationGuard.enforce(
                    ctx.getMode(),
                    ctx.getDownload() != null ? ctx.getDownload().getDurationSeconds() : task.getDurationSeconds(),
                    videoProperties.getUnderstanding(),
                    taskId);
            if (next != ctx.getMode()) {
                ctx.setMode(next);
                ctx.getTask().setUnderstandingMode(next.wireValue());
                ctx.getTask().setUpdatedAt(LocalDateTime.now());
                videoTaskMapper.updateById(ctx.getTask());
            }

            if (shouldPause(taskId, ctx.getTask(), pipelineStart)) {
                return;
            }

            for (VideoPipelineStep step : afterDownloadSteps) {
                if (!step.applies(ctx.getMode())) {
                    continue;
                }
                if (shouldPause(taskId, ctx.getTask(), pipelineStart)) {
                    return;
                }
                runStep(step, ctx);
                persistStep(ctx);
                if (shouldPause(taskId, ctx.getTask(), pipelineStart)) {
                    return;
                }
            }

            finishSuccess(ctx);
        } catch (Exception e) {
            if (taskScheduler.isPauseRequested(taskId) || isPausedInDb(taskId)) {
                markPaused(ctx.getTask(), pipelineStart, "用户暂停（当前步骤被中断）");
                storageService.cleanupAfterFailure(ctx.getTask());
                return;
            }
            log.error("视频任务失败: taskId={}", taskId, e);
            VideoTaskEntity failed = ctx.getTask();
            failed.setStatus(VideoTaskStatus.FAILED.name());
            failed.setCurrentStep("失败");
            failed.setErrorMessage(VideoTexts.truncate(e.getMessage(), 1000));
            failed.setFinishedAt(LocalDateTime.now());
            failed.setTotalDurationMs(System.currentTimeMillis() - pipelineStart);
            failed.setUpdatedAt(LocalDateTime.now());
            videoTaskMapper.updateById(failed);
            eventPublisher.publishEntity(failed, VideoTaskEventPublisher.TYPE_STATUS);
            storageService.cleanupAfterFailure(failed);
        } finally {
            taskScheduler.markFinished(taskId);
        }
    }

    private void runStep(VideoPipelineStep step, VideoPipelineContext ctx) throws Exception {
        VideoTaskEntity task = ctx.getTask();
        step.prepare(ctx);
        updateStatus(task, step.runningStatus(), step.stepLabel(ctx));
        step.execute(ctx);
    }

    private void persistStep(VideoPipelineContext ctx) {
        VideoTaskEntity task = ctx.getTask();
        task.setUpdatedAt(LocalDateTime.now());
        videoTaskMapper.updateById(task);
        eventPublisher.publishEntity(task, VideoTaskEventPublisher.TYPE_STATUS);
    }

    private void finishSuccess(VideoPipelineContext ctx) throws Exception {
        VideoTaskEntity task = ctx.getTask();
        storageService.persistAndCleanupAfterSuccess(task);
        task.setStatus(VideoTaskStatus.SUCCESS.name());
        task.setCurrentStep(ctx.isDegraded() ? "完成（已降级为纯音频总结）" : "完成");
        task.setFinishedAt(LocalDateTime.now());
        task.setTotalDurationMs(System.currentTimeMillis() - ctx.getPipelineStartMs());
        task.setUpdatedAt(LocalDateTime.now());
        task.setErrorMessage(null);
        videoTaskMapper.updateById(task);
        eventPublisher.publishEntity(task, VideoTaskEventPublisher.TYPE_STATUS);
        log.info("视频任务完成: taskId={}, title={}, mode={}, degraded={}, total={}ms, videoKey={}",
                task.getId(), task.getTitle(), ctx.getMode().wireValue(), ctx.isDegraded(),
                task.getTotalDurationMs(), task.getVideoPath());
    }

    private void finishDownloadOnly(VideoTaskEntity task, String taskIdStr, long pipelineStart)
            throws Exception {
        VideoSummaryResponse response = new VideoSummaryResponse();
        response.setVideoId(taskIdStr);
        response.setTitle(task.getTitle() != null ? task.getTitle() : "未知标题");
        response.setDuration(task.getDurationSeconds());
        response.setSourceUrl(task.getSourceUrl());
        response.setUnderstandingMode(UnderstandingMode.DOWNLOAD_ONLY.wireValue());
        response.setDegraded(false);

        task.setResultJson(objectMapper.writeValueAsString(response));
        task.setDegraded(0);
        storageService.persistAndCleanupAfterSuccess(task);

        task.setStatus(VideoTaskStatus.SUCCESS.name());
        task.setCurrentStep("下载完成");
        task.setFinishedAt(LocalDateTime.now());
        task.setTotalDurationMs(System.currentTimeMillis() - pipelineStart);
        task.setUpdatedAt(LocalDateTime.now());
        task.setErrorMessage(null);
        videoTaskMapper.updateById(task);
        eventPublisher.publishEntity(task, VideoTaskEventPublisher.TYPE_STATUS);
        log.info("仅下载任务完成: taskId={}, title={}, total={}ms, videoKey={}",
                task.getId(), task.getTitle(), task.getTotalDurationMs(), task.getVideoPath());
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
        storageService.cleanupAfterFailure(latest);
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
}
