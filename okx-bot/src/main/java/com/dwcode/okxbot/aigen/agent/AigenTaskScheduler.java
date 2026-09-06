package com.dwcode.okxbot.aigen.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.entity.AigenTaskEntity;
import com.dwcode.okxbot.aigen.enums.AigenTaskStatus;
import com.dwcode.okxbot.aigen.event.AigenTaskEventPublisher;
import com.dwcode.okxbot.aigen.mapper.AigenTaskMapper;
import com.dwcode.okxbot.common.task.TaskSlotKernel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * aigen 任务调度：并发槽位 + PENDING FIFO + 暂停/取消协作标记。
 */
@Slf4j
@Component
public class AigenTaskScheduler {

    private final AigenTaskMapper aigenTaskMapper;
    private final AigenTaskAsyncRunner asyncRunner;
    private final AigenProperties aigenProperties;
    private final AigenTaskEventPublisher eventPublisher;
    private final TaskSlotKernel slots = new TaskSlotKernel("aigen");

    public AigenTaskScheduler(AigenTaskMapper aigenTaskMapper,
                              @Lazy AigenTaskAsyncRunner asyncRunner,
                              AigenProperties aigenProperties,
                              AigenTaskEventPublisher eventPublisher) {
        this.aigenTaskMapper = aigenTaskMapper;
        this.asyncRunner = asyncRunner;
        this.aigenProperties = aigenProperties;
        this.eventPublisher = eventPublisher;
    }

    public void notifyPending() {
        tryStartNext();
    }

    /**
     * 进程重启后，内存槽位清空，但 DB 里可能残留 PLANNING/ASSET/RENDERING，
     * 会永久占满 max-concurrent-tasks=1，导致新任务一直「排队中」。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanRunningTasks() {
        List<AigenTaskEntity> orphans = aigenTaskMapper.selectList(
                new LambdaQueryWrapper<AigenTaskEntity>()
                        .in(AigenTaskEntity::getStatus,
                                AigenTaskStatus.PLANNING.name(),
                                AigenTaskStatus.ASSET_GENERATING.name(),
                                AigenTaskStatus.RENDERING.name())
        );
        if (orphans.isEmpty()) {
            tryStartNext();
            return;
        }
        log.warn("发现 {} 个中断的 aigen 进行中任务，标记 FAILED 并调度排队", orphans.size());
        for (AigenTaskEntity t : orphans) {
            t.setStatus(AigenTaskStatus.FAILED.name());
            t.setCurrentStep("服务重启，任务中断");
            t.setErrorMessage("服务重启导致任务中断，请点击重试");
            t.setFinishedAt(LocalDateTime.now());
            t.setUpdatedAt(LocalDateTime.now());
            aigenTaskMapper.updateById(t);
            eventPublisher.publishEntity(t, AigenTaskEventPublisher.TYPE_STATUS);
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
        int max = Math.max(1, aigenProperties.getMaxConcurrentTasks());
        int runningLike = countRunningInDb();
        int slotsFree = slots.freeSlots(max, runningLike);
        if (slotsFree <= 0) {
            return;
        }
        List<AigenTaskEntity> pending = aigenTaskMapper.selectList(
                new LambdaQueryWrapper<AigenTaskEntity>()
                        .eq(AigenTaskEntity::getStatus, AigenTaskStatus.PENDING.name())
                        .orderByAsc(AigenTaskEntity::getCreatedAt)
                        .last("LIMIT " + TaskSlotKernel.pendingFetchLimit(slotsFree))
        );
        slots.startPending(max, runningLike, pending,
                AigenTaskEntity::getId, null, t -> asyncRunner.runAsync(t.getId()));
    }

    private int countRunningInDb() {
        Long cnt = aigenTaskMapper.selectCount(
                new LambdaQueryWrapper<AigenTaskEntity>()
                        .in(AigenTaskEntity::getStatus,
                                AigenTaskStatus.PLANNING.name(),
                                AigenTaskStatus.ASSET_GENERATING.name(),
                                AigenTaskStatus.RENDERING.name())
        );
        return cnt == null ? 0 : cnt.intValue();
    }
}
