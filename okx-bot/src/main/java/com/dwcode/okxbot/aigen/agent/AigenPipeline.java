package com.dwcode.okxbot.aigen.agent;

import com.dwcode.okxbot.aigen.agent.step.AssetStep;
import com.dwcode.okxbot.aigen.agent.step.PipelineContext;
import com.dwcode.okxbot.aigen.agent.step.PipelineStep;
import com.dwcode.okxbot.aigen.agent.step.PlanStep;
import com.dwcode.okxbot.aigen.agent.step.RenderStep;
import com.dwcode.okxbot.aigen.domain.StoryboardDto;
import com.dwcode.okxbot.aigen.domain.shot.ShotlistDto;
import com.dwcode.okxbot.aigen.entity.AigenTaskEntity;
import com.dwcode.okxbot.aigen.enums.AigenTaskStatus;
import com.dwcode.okxbot.aigen.event.AigenTaskEventPublisher;
import com.dwcode.okxbot.aigen.mapper.AigenTaskMapper;
import com.dwcode.okxbot.aigen.service.AigenStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 视频生成流水线：Plan → Asset → Render。
 * 暂停/取消仅在步骤边界生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AigenPipeline {

    private final AigenTaskMapper aigenTaskMapper;
    private final AigenTaskScheduler taskScheduler;
    private final AigenTaskEventPublisher eventPublisher;
    private final AigenStorageService storageService;
    private final ObjectMapper objectMapper;
    private final PlanStep planStep;
    private final AssetStep assetStep;
    private final RenderStep renderStep;

    public void run(Long taskId) {
        AigenTaskEntity task = aigenTaskMapper.selectById(taskId);
        if (task == null) {
            log.error("aigen 任务不存在: {}", taskId);
            taskScheduler.markFinished(taskId);
            return;
        }

        if (AigenTaskStatus.CANCELLED.name().equals(task.getStatus())
                || AigenTaskStatus.SUCCESS.name().equals(task.getStatus())
                || AigenTaskStatus.PAUSED.name().equals(task.getStatus())) {
            taskScheduler.markFinished(taskId);
            return;
        }

        taskScheduler.markRunning(taskId);
        taskScheduler.clearCancelRequest(taskId);
        taskScheduler.clearPauseRequest(taskId);

        long pipelineStart = System.currentTimeMillis();
        task.setStartedAt(LocalDateTime.now());
        // 立刻离开 PENDING，避免前端一直显示「排队中」
        task.setStatus(AigenTaskStatus.PLANNING.name());
        task.setCurrentStep("任务已启动，准备规划…");
        task.setProgress(5);
        // 重试后重新跑：清空错误与旧耗时展示干扰
        task.setErrorMessage("");
        task.setUpdatedAt(LocalDateTime.now());
        aigenTaskMapper.updateById(task);
        eventPublisher.publishEntity(task, AigenTaskEventPublisher.TYPE_STATUS);

        PipelineContext ctx = new PipelineContext();
        ctx.setTaskId(taskId);
        ctx.setTask(task);
        ctx.setPipelineStartMs(pipelineStart);
        ctx.setCancelCheck(() ->
                taskScheduler.isCancelRequested(taskId) || taskScheduler.isPauseRequested(taskId));

        List<PipelineStep> steps = List.of(planStep, assetStep, renderStep);

        try {
            Path workDir = storageService.ensureTaskDir(String.valueOf(taskId));
            ctx.setWorkDir(workDir);
            task.setWorkDir(workDir.toAbsolutePath().toString());
            aigenTaskMapper.updateById(task);

            if (task.getStoryboardJson() != null && !task.getStoryboardJson().isBlank()) {
                try {
                    if (ctx.isVisualMode()) {
                        ctx.setShotlist(objectMapper.readValue(task.getStoryboardJson(), ShotlistDto.class));
                    } else {
                        ctx.setStoryboard(objectMapper.readValue(task.getStoryboardJson(), StoryboardDto.class));
                    }
                } catch (Exception ignored) {
                    // ignore
                }
            }

            for (PipelineStep step : steps) {
                if (shouldStopAtBoundary(taskId, task, pipelineStart)) {
                    return;
                }
                // 暂停请求期间不要把状态改回 RUNNING 文案以外的进行中（对齐视频提取）
                if (!taskScheduler.isPauseRequested(taskId)) {
                    updateStatus(task, step.runningStatus(), step.stepLabel(), step.progressPercent());
                }
                long t0 = System.currentTimeMillis();
                step.execute(ctx);
                long cost = System.currentTimeMillis() - t0;

                // 步骤可能已写入 outputPath / storyboard 等；若整行 reload 会冲掉尚未落库的字段
                AigenTaskEntity afterStep = ctx.getTask();
                if (step instanceof PlanStep) {
                    afterStep.setPlanDurationMs(cost);
                } else if (step instanceof AssetStep) {
                    afterStep.setAssetDurationMs(cost);
                } else if (step instanceof RenderStep) {
                    afterStep.setRenderDurationMs(cost);
                }
                afterStep.setUpdatedAt(LocalDateTime.now());
                aigenTaskMapper.updateById(afterStep);

                // 再读一次以合并暂停等并发状态，但保留本步产物字段
                AigenTaskEntity latest = aigenTaskMapper.selectById(taskId);
                if (latest != null) {
                    mergeStepOutputs(latest, afterStep);
                    task = latest;
                    ctx.setTask(task);
                } else {
                    task = afterStep;
                }
                eventPublisher.publishEntity(task, AigenTaskEventPublisher.TYPE_STATUS);

                if (shouldStopAtBoundary(taskId, task, pipelineStart)) {
                    return;
                }
            }

            // 成功前再扫一次磁盘：兼容历史「渲了文件但 path 未写入」的情况
            ensureOutputPathFromDisk(task, workDir);

            // 诚实成功：无可用成片不得标 SUCCESS
            if (!isPlayableOutput(task)) {
                throw new IllegalStateException(
                        "渲染流程结束但未产出可播放的 output.mp4（请检查 aigen-remotion / ffmpeg / 磁盘路径）");
            }
            task.setStatus(AigenTaskStatus.SUCCESS.name());
            task.setCurrentStep("生成完成");
            task.setProgress(100);
            task.setFinishedAt(LocalDateTime.now());
            task.setTotalDurationMs(System.currentTimeMillis() - pipelineStart);
            // 显式空串，避免 Jackson non_null 省略字段导致前端残留旧 errorMessage
            task.setErrorMessage("");
            task.setUpdatedAt(LocalDateTime.now());
            aigenTaskMapper.updateById(task);
            eventPublisher.publishEntity(task, AigenTaskEventPublisher.TYPE_STATUS);
            log.info("aigen 流水线完成: taskId={}, plan={}ms asset={}ms render={}ms total={}ms out={}",
                    taskId, task.getPlanDurationMs(), task.getAssetDurationMs(),
                    task.getRenderDurationMs(), task.getTotalDurationMs(), task.getOutputPath());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (taskScheduler.isPauseRequested(taskId)) {
                markPaused(task, pipelineStart, "用户暂停（当前步骤被中断）");
            } else {
                markCancelled(task, pipelineStart);
            }
        } catch (Exception e) {
            if (taskScheduler.isPauseRequested(taskId) || isPausedInDb(taskId)) {
                markPaused(task, pipelineStart, "用户暂停（当前步骤被中断）");
                return;
            }
            if (taskScheduler.isCancelRequested(taskId)) {
                markCancelled(task, pipelineStart);
                return;
            }
            log.error("aigen 流水线失败: taskId={}", taskId, e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (msg.contains("cancelled") || msg.contains("paused")) {
                if (taskScheduler.isPauseRequested(taskId)) {
                    markPaused(task, pipelineStart, "用户已暂停");
                } else {
                    markCancelled(task, pipelineStart);
                }
            } else {
                failTask(task, msg, System.currentTimeMillis() - pipelineStart);
            }
        } finally {
            taskScheduler.markFinished(taskId);
        }
    }

    /**
     * 步骤边界：优先处理取消，其次暂停。
     */
    private boolean shouldStopAtBoundary(Long taskId, AigenTaskEntity task, long pipelineStart) {
        if (taskScheduler.isCancelRequested(taskId)) {
            markCancelled(task, pipelineStart);
            return true;
        }
        if (taskScheduler.isPauseRequested(taskId) || isPausedInDb(taskId)) {
            markPaused(task, pipelineStart, "用户已暂停，可点「重试」继续");
            return true;
        }
        return false;
    }

    private boolean isPausedInDb(Long taskId) {
        AigenTaskEntity latest = aigenTaskMapper.selectById(taskId);
        return latest != null && AigenTaskStatus.PAUSED.name().equals(latest.getStatus());
    }

    private void markPaused(AigenTaskEntity task, long pipelineStart, String step) {
        AigenTaskEntity latest = aigenTaskMapper.selectById(task.getId());
        if (latest == null) {
            return;
        }
        if (AigenTaskStatus.SUCCESS.name().equals(latest.getStatus())
                || AigenTaskStatus.FAILED.name().equals(latest.getStatus())
                || AigenTaskStatus.CANCELLED.name().equals(latest.getStatus())) {
            return;
        }
        latest.setStatus(AigenTaskStatus.PAUSED.name());
        latest.setCurrentStep(step);
        latest.setErrorMessage("");
        latest.setFinishedAt(LocalDateTime.now());
        latest.setTotalDurationMs(System.currentTimeMillis() - pipelineStart);
        latest.setUpdatedAt(LocalDateTime.now());
        aigenTaskMapper.updateById(latest);
        task.setStatus(latest.getStatus());
        task.setCurrentStep(latest.getCurrentStep());
        task.setTotalDurationMs(latest.getTotalDurationMs());
        eventPublisher.publishEntity(latest, AigenTaskEventPublisher.TYPE_STATUS);
        log.info("aigen 任务已暂停: taskId={}, step={}", task.getId(), step);
    }

    private void markCancelled(AigenTaskEntity task, long pipelineStart) {
        AigenTaskEntity latest = aigenTaskMapper.selectById(task.getId());
        if (latest == null) {
            return;
        }
        latest.setStatus(AigenTaskStatus.CANCELLED.name());
        latest.setCurrentStep("已取消");
        latest.setErrorMessage("");
        latest.setFinishedAt(LocalDateTime.now());
        latest.setTotalDurationMs(System.currentTimeMillis() - pipelineStart);
        latest.setUpdatedAt(LocalDateTime.now());
        aigenTaskMapper.updateById(latest);
        eventPublisher.publishEntity(latest, AigenTaskEventPublisher.TYPE_STATUS);
    }

    private void failTask(AigenTaskEntity task, String message, long totalMs) {
        AigenTaskEntity latest = aigenTaskMapper.selectById(task.getId());
        if (latest == null) {
            latest = task;
        }
        latest.setStatus(AigenTaskStatus.FAILED.name());
        latest.setCurrentStep("失败");
        latest.setErrorMessage(truncate(message, 1000));
        latest.setFinishedAt(LocalDateTime.now());
        latest.setTotalDurationMs(totalMs);
        latest.setUpdatedAt(LocalDateTime.now());
        aigenTaskMapper.updateById(latest);
        eventPublisher.publishEntity(latest, AigenTaskEventPublisher.TYPE_STATUS);
    }

    private void updateStatus(AigenTaskEntity task, AigenTaskStatus status, String step, int progress) {
        if (taskScheduler.isPauseRequested(task.getId()) || taskScheduler.isCancelRequested(task.getId())) {
            return;
        }
        task.setStatus(status.name());
        task.setCurrentStep(step);
        task.setProgress(progress);
        // 进行中不展示历史错误
        if (task.getErrorMessage() != null && !task.getErrorMessage().isBlank()) {
            task.setErrorMessage("");
        }
        task.setUpdatedAt(LocalDateTime.now());
        aigenTaskMapper.updateById(task);
        eventPublisher.publishEntity(task, AigenTaskEventPublisher.TYPE_STATUS);
    }

    /**
     * 把步骤写在内存实体上的产物字段保留到 DB 最新行。
     */
    private static void mergeStepOutputs(AigenTaskEntity target, AigenTaskEntity fromStep) {
        if (fromStep.getOutputPath() != null && !fromStep.getOutputPath().isBlank()) {
            target.setOutputPath(fromStep.getOutputPath());
        }
        if (fromStep.getOutputSizeBytes() != null) {
            target.setOutputSizeBytes(fromStep.getOutputSizeBytes());
        }
        if (fromStep.getStoryboardJson() != null) {
            target.setStoryboardJson(fromStep.getStoryboardJson());
        }
        if (fromStep.getStoryboardPath() != null) {
            target.setStoryboardPath(fromStep.getStoryboardPath());
        }
        if (fromStep.getDurationSeconds() != null) {
            target.setDurationSeconds(fromStep.getDurationSeconds());
        }
        if (fromStep.getTitle() != null && !fromStep.getTitle().isBlank()) {
            target.setTitle(fromStep.getTitle());
        }
        if (fromStep.getPlanDurationMs() != null) {
            target.setPlanDurationMs(fromStep.getPlanDurationMs());
        }
        if (fromStep.getAssetDurationMs() != null) {
            target.setAssetDurationMs(fromStep.getAssetDurationMs());
        }
        if (fromStep.getRenderDurationMs() != null) {
            target.setRenderDurationMs(fromStep.getRenderDurationMs());
        }
        if (fromStep.getWorkDir() != null) {
            target.setWorkDir(fromStep.getWorkDir());
        }
        if (fromStep.getShotCount() != null) {
            target.setShotCount(fromStep.getShotCount());
        }
        if (fromStep.getAssetDoneCount() != null) {
            target.setAssetDoneCount(fromStep.getAssetDoneCount());
        }
        if (fromStep.getPipelineMode() != null) {
            target.setPipelineMode(fromStep.getPipelineMode());
        }
        if (fromStep.getAudioMode() != null) {
            target.setAudioMode(fromStep.getAudioMode());
        }
        if (fromStep.getStylePreset() != null) {
            target.setStylePreset(fromStep.getStylePreset());
        }
    }

    private void ensureOutputPathFromDisk(AigenTaskEntity task, Path workDir) {
        try {
            if (task.getOutputPath() != null && !task.getOutputPath().isBlank()
                    && java.nio.file.Files.isRegularFile(Path.of(task.getOutputPath()))) {
                long sz = java.nio.file.Files.size(Path.of(task.getOutputPath()));
                if (sz >= 1024L) {
                    task.setOutputSizeBytes(sz);
                    return;
                }
            }
            Path mp4 = workDir.resolve("output.mp4");
            if (java.nio.file.Files.isRegularFile(mp4)
                    && java.nio.file.Files.size(mp4) >= 1024L) {
                task.setOutputPath(mp4.toAbsolutePath().normalize().toString());
                task.setOutputSizeBytes(java.nio.file.Files.size(mp4));
                log.info("从磁盘补全 outputPath: {}", task.getOutputPath());
            }
        } catch (Exception e) {
            log.debug("ensureOutputPathFromDisk: {}", e.getMessage());
        }
    }

    /** 成片至少 1KB 的常规文件，避免空壳 SUCCESS。 */
    private static boolean isPlayableOutput(AigenTaskEntity task) {
        if (task == null || task.getOutputPath() == null || task.getOutputPath().isBlank()) {
            return false;
        }
        try {
            Path p = Path.of(task.getOutputPath());
            return java.nio.file.Files.isRegularFile(p) && java.nio.file.Files.size(p) >= 1024L;
        } catch (Exception e) {
            return false;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}

