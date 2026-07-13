package com.dwcode.okxbot.imggen.agent;

import com.dwcode.okxbot.imggen.agent.step.EnhanceStep;
import com.dwcode.okxbot.imggen.agent.step.GenerateStep;
import com.dwcode.okxbot.imggen.agent.step.PipelineContext;
import com.dwcode.okxbot.imggen.agent.step.PipelineStep;
import com.dwcode.okxbot.imggen.entity.ImgGenTaskEntity;
import com.dwcode.okxbot.imggen.enums.ImgGenTaskStatus;
import com.dwcode.okxbot.imggen.event.ImgGenTaskEventPublisher;
import com.dwcode.okxbot.imggen.mapper.ImgGenTaskMapper;
import com.dwcode.okxbot.imggen.service.ImgGenStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImgGenPipeline {

    private final ImgGenTaskMapper taskMapper;
    private final ImgGenTaskScheduler taskScheduler;
    private final ImgGenTaskEventPublisher eventPublisher;
    private final ImgGenStorageService storageService;
    private final EnhanceStep enhanceStep;
    private final GenerateStep generateStep;

    public void run(Long taskId) {
        ImgGenTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            log.error("imggen 任务不存在: {}", taskId);
            taskScheduler.markFinished(taskId);
            return;
        }
        if (ImgGenTaskStatus.CANCELLED.name().equals(task.getStatus())
                || ImgGenTaskStatus.SUCCESS.name().equals(task.getStatus())
                || ImgGenTaskStatus.PAUSED.name().equals(task.getStatus())) {
            taskScheduler.markFinished(taskId);
            return;
        }

        taskScheduler.markRunning(taskId);
        taskScheduler.clearCancelRequest(taskId);
        taskScheduler.clearPauseRequest(taskId);

        long pipelineStart = System.currentTimeMillis();
        task.setStartedAt(LocalDateTime.now());
        task.setStatus(ImgGenTaskStatus.GENERATING.name());
        task.setCurrentStep("任务已启动…");
        task.setProgress(5);
        task.setErrorMessage("");
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        eventPublisher.publishEntity(task, ImgGenTaskEventPublisher.TYPE_STATUS);

        PipelineContext ctx = new PipelineContext();
        ctx.setTaskId(taskId);
        ctx.setTask(task);
        ctx.setPipelineStartMs(pipelineStart);
        ctx.setFinalPrompt(task.getPrompt());
        ctx.setCancelCheck(() ->
                taskScheduler.isCancelRequested(taskId) || taskScheduler.isPauseRequested(taskId));

        try {
            Path workDir = storageService.ensureTaskDir(String.valueOf(taskId));
            ctx.setWorkDir(workDir);
            task.setWorkDir(workDir.toAbsolutePath().toString());
            storageService.writeRequestSnapshot(workDir, task.getPrompt(),
                    task.getAspectRatio(), task.getN() != null ? task.getN() : 1);
            taskMapper.updateById(task);

            List<PipelineStep> steps = new ArrayList<>();
            if (enhanceStep.shouldRun(ctx)) {
                steps.add(enhanceStep);
            }
            steps.add(generateStep);

            for (PipelineStep step : steps) {
                if (shouldStopAtBoundary(taskId, task, pipelineStart)) {
                    return;
                }
                if (!taskScheduler.isPauseRequested(taskId)) {
                    updateStatus(task, step.runningStatus(), step.stepLabel(), step.progressPercent());
                }
                long t0 = System.currentTimeMillis();
                step.execute(ctx);
                long cost = System.currentTimeMillis() - t0;

                ImgGenTaskEntity after = ctx.getTask();
                if (step instanceof EnhanceStep) {
                    after.setEnhanceDurationMs(cost);
                } else if (step instanceof GenerateStep) {
                    after.setGenerateDurationMs(cost);
                }
                after.setUpdatedAt(LocalDateTime.now());
                taskMapper.updateById(after);

                ImgGenTaskEntity latest = taskMapper.selectById(taskId);
                if (latest != null) {
                    mergeOutputs(latest, after);
                    task = latest;
                    ctx.setTask(task);
                } else {
                    task = after;
                }
                eventPublisher.publishEntity(task, ImgGenTaskEventPublisher.TYPE_STATUS);

                if (shouldStopAtBoundary(taskId, task, pipelineStart)) {
                    return;
                }
            }

            task.setStatus(ImgGenTaskStatus.SUCCESS.name());
            task.setCurrentStep("生成完成");
            task.setProgress(100);
            task.setFinishedAt(LocalDateTime.now());
            task.setTotalDurationMs(System.currentTimeMillis() - pipelineStart);
            task.setErrorMessage("");
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            eventPublisher.publishEntity(task, ImgGenTaskEventPublisher.TYPE_STATUS);
            log.info("imggen 完成: taskId={} total={}ms", taskId, task.getTotalDurationMs());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (taskScheduler.isPauseRequested(taskId)) {
                markPaused(task, pipelineStart, "用户暂停");
            } else {
                markCancelled(task, pipelineStart);
            }
        } catch (Exception e) {
            if (taskScheduler.isPauseRequested(taskId)) {
                markPaused(task, pipelineStart, "用户暂停");
                return;
            }
            if (taskScheduler.isCancelRequested(taskId)) {
                markCancelled(task, pipelineStart);
                return;
            }
            log.error("imggen 流水线失败: taskId={}", taskId, e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            failTask(task, msg, System.currentTimeMillis() - pipelineStart);
        } finally {
            taskScheduler.markFinished(taskId);
        }
    }

    private boolean shouldStopAtBoundary(Long taskId, ImgGenTaskEntity task, long pipelineStart) {
        if (taskScheduler.isCancelRequested(taskId)) {
            markCancelled(task, pipelineStart);
            return true;
        }
        if (taskScheduler.isPauseRequested(taskId)) {
            markPaused(task, pipelineStart, "用户已暂停，可点「重试」继续");
            return true;
        }
        return false;
    }

    private void markPaused(ImgGenTaskEntity task, long pipelineStart, String step) {
        ImgGenTaskEntity latest = taskMapper.selectById(task.getId());
        if (latest == null) {
            return;
        }
        if (isTerminal(latest.getStatus())) {
            return;
        }
        latest.setStatus(ImgGenTaskStatus.PAUSED.name());
        latest.setCurrentStep(step);
        latest.setErrorMessage("");
        latest.setFinishedAt(LocalDateTime.now());
        latest.setTotalDurationMs(System.currentTimeMillis() - pipelineStart);
        latest.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(latest);
        eventPublisher.publishEntity(latest, ImgGenTaskEventPublisher.TYPE_STATUS);
    }

    private void markCancelled(ImgGenTaskEntity task, long pipelineStart) {
        ImgGenTaskEntity latest = taskMapper.selectById(task.getId());
        if (latest == null) {
            return;
        }
        latest.setStatus(ImgGenTaskStatus.CANCELLED.name());
        latest.setCurrentStep("已取消");
        latest.setErrorMessage("");
        latest.setFinishedAt(LocalDateTime.now());
        latest.setTotalDurationMs(System.currentTimeMillis() - pipelineStart);
        latest.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(latest);
        eventPublisher.publishEntity(latest, ImgGenTaskEventPublisher.TYPE_STATUS);
    }

    private void failTask(ImgGenTaskEntity task, String message, long totalMs) {
        ImgGenTaskEntity latest = taskMapper.selectById(task.getId());
        if (latest == null) {
            latest = task;
        }
        latest.setStatus(ImgGenTaskStatus.FAILED.name());
        latest.setCurrentStep("失败");
        latest.setErrorMessage(truncate(message, 1000));
        latest.setFinishedAt(LocalDateTime.now());
        latest.setTotalDurationMs(totalMs);
        latest.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(latest);
        eventPublisher.publishEntity(latest, ImgGenTaskEventPublisher.TYPE_STATUS);
    }

    private void updateStatus(ImgGenTaskEntity task, ImgGenTaskStatus status, String step, int progress) {
        if (taskScheduler.isPauseRequested(task.getId()) || taskScheduler.isCancelRequested(task.getId())) {
            return;
        }
        task.setStatus(status.name());
        task.setCurrentStep(step);
        task.setProgress(progress);
        if (task.getErrorMessage() != null && !task.getErrorMessage().isBlank()) {
            task.setErrorMessage("");
        }
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        eventPublisher.publishEntity(task, ImgGenTaskEventPublisher.TYPE_STATUS);
    }

    private static void mergeOutputs(ImgGenTaskEntity target, ImgGenTaskEntity from) {
        if (from.getEnhancedPrompt() != null) {
            target.setEnhancedPrompt(from.getEnhancedPrompt());
        }
        if (from.getResultJson() != null) {
            target.setResultJson(from.getResultJson());
        }
        if (from.getCoverPath() != null) {
            target.setCoverPath(from.getCoverPath());
        }
        if (from.getWorkDir() != null) {
            target.setWorkDir(from.getWorkDir());
        }
        if (from.getProviderRequestId() != null) {
            target.setProviderRequestId(from.getProviderRequestId());
        }
        if (from.getEnhanceDurationMs() != null) {
            target.setEnhanceDurationMs(from.getEnhanceDurationMs());
        }
        if (from.getGenerateDurationMs() != null) {
            target.setGenerateDurationMs(from.getGenerateDurationMs());
        }
    }

    private static boolean isTerminal(String status) {
        return ImgGenTaskStatus.SUCCESS.name().equals(status)
                || ImgGenTaskStatus.FAILED.name().equals(status)
                || ImgGenTaskStatus.CANCELLED.name().equals(status);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
