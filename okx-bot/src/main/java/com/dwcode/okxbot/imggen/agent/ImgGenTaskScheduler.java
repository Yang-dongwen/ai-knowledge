package com.dwcode.okxbot.imggen.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.common.task.TaskSlotKernel;
import com.dwcode.okxbot.imggen.config.ImgGenProperties;
import com.dwcode.okxbot.imggen.entity.ImgGenTaskEntity;
import com.dwcode.okxbot.imggen.enums.ImgGenTaskStatus;
import com.dwcode.okxbot.imggen.event.ImgGenTaskEventPublisher;
import com.dwcode.okxbot.imggen.mapper.ImgGenTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
public class ImgGenTaskScheduler {

    private final ImgGenTaskMapper taskMapper;
    private final ImgGenTaskAsyncRunner asyncRunner;
    private final ImgGenProperties properties;
    private final ImgGenTaskEventPublisher eventPublisher;
    private final TaskSlotKernel slots = new TaskSlotKernel("imggen");

    public ImgGenTaskScheduler(ImgGenTaskMapper taskMapper,
                               @Lazy ImgGenTaskAsyncRunner asyncRunner,
                               ImgGenProperties properties,
                               ImgGenTaskEventPublisher eventPublisher) {
        this.taskMapper = taskMapper;
        this.asyncRunner = asyncRunner;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    public void notifyPending() {
        tryStartNext();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanRunningTasks() {
        if (!properties.isEnabled()) {
            return;
        }
        List<ImgGenTaskEntity> orphans = taskMapper.selectList(
                new LambdaQueryWrapper<ImgGenTaskEntity>()
                        .in(ImgGenTaskEntity::getStatus,
                                ImgGenTaskStatus.PROMPT_ENHANCING.name(),
                                ImgGenTaskStatus.GENERATING.name())
        );
        if (orphans.isEmpty()) {
            tryStartNext();
            return;
        }
        log.warn("发现 {} 个中断的 imggen 进行中任务，标记 FAILED", orphans.size());
        for (ImgGenTaskEntity t : orphans) {
            t.setStatus(ImgGenTaskStatus.FAILED.name());
            t.setCurrentStep("服务重启，任务中断");
            t.setErrorMessage("服务重启导致任务中断，请点击重试");
            t.setFinishedAt(LocalDateTime.now());
            t.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(t);
            eventPublisher.publishEntity(t, ImgGenTaskEventPublisher.TYPE_STATUS);
        }
        tryStartNext();
    }

    public void markRunning(Long taskId) {
        slots.markRunning(taskId);
    }

    public void markFinished(Long taskId) {
        slots.release(taskId);
        tryStartNext();
    }

    public void requestCancel(Long taskId) {
        slots.requestCancel(taskId);
    }

    public boolean isCancelRequested(Long taskId) {
        return slots.isCancelRequested(taskId);
    }

    public void clearCancelRequest(Long taskId) {
        slots.clearCancelRequest(taskId);
    }

    public void requestPause(Long taskId) {
        slots.requestPause(taskId);
    }

    public boolean isPauseRequested(Long taskId) {
        return slots.isPauseRequested(taskId);
    }

    public void clearPauseRequest(Long taskId) {
        slots.clearPauseRequest(taskId);
    }

    public synchronized void tryStartNext() {
        if (!properties.isEnabled()) {
            return;
        }
        int max = Math.max(1, properties.getMaxConcurrentTasks());
        int runningLike = countRunningInDb();
        int slotsFree = slots.freeSlots(max, runningLike);
        if (slotsFree <= 0) {
            return;
        }
        List<ImgGenTaskEntity> pending = taskMapper.selectList(
                new LambdaQueryWrapper<ImgGenTaskEntity>()
                        .eq(ImgGenTaskEntity::getStatus, ImgGenTaskStatus.PENDING.name())
                        .orderByAsc(ImgGenTaskEntity::getCreatedAt)
                        .last("LIMIT " + TaskSlotKernel.pendingFetchLimit(slotsFree))
        );
        slots.startPending(max, runningLike, pending,
                ImgGenTaskEntity::getId, null, t -> asyncRunner.runAsync(t.getId()));
    }

    private int countRunningInDb() {
        Long cnt = taskMapper.selectCount(
                new LambdaQueryWrapper<ImgGenTaskEntity>()
                        .in(ImgGenTaskEntity::getStatus,
                                ImgGenTaskStatus.PROMPT_ENHANCING.name(),
                                ImgGenTaskStatus.GENERATING.name())
        );
        return cnt == null ? 0 : cnt.intValue();
    }
}
