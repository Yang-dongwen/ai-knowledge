package com.dwcode.okxbot.aigen.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.entity.AigenTaskEntity;
import com.dwcode.okxbot.aigen.enums.AigenTaskStatus;
import com.dwcode.okxbot.aigen.mapper.AigenTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * aigen 任务调度：并发槽位 + PENDING FIFO + 暂停/取消协作标记。
 */
@Slf4j
@Component
public class AigenTaskScheduler {

    private final AigenTaskMapper aigenTaskMapper;
    private final AigenTaskAsyncRunner asyncRunner;
    private final AigenProperties aigenProperties;

    private final Set<Long> activeTaskIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> cancelRequested = ConcurrentHashMap.newKeySet();
    private final Set<Long> pauseRequested = ConcurrentHashMap.newKeySet();

    public AigenTaskScheduler(AigenTaskMapper aigenTaskMapper,
                              @Lazy AigenTaskAsyncRunner asyncRunner,
                              AigenProperties aigenProperties) {
        this.aigenTaskMapper = aigenTaskMapper;
        this.asyncRunner = asyncRunner;
        this.aigenProperties = aigenProperties;
    }

    public void notifyPending() {
        tryStartNext();
    }

    public void markRunning(Long taskId) {
        activeTaskIds.add(taskId);
    }

    public void markFinished(Long taskId) {
        activeTaskIds.remove(taskId);
        cancelRequested.remove(taskId);
        pauseRequested.remove(taskId);
        tryStartNext();
    }

    public void requestCancel(Long taskId) {
        cancelRequested.add(taskId);
        log.info("已标记取消 aigen 任务: taskId={}", taskId);
    }

    public boolean isCancelRequested(Long taskId) {
        return cancelRequested.contains(taskId);
    }

    public void clearCancelRequest(Long taskId) {
        cancelRequested.remove(taskId);
    }

    public void requestPause(Long taskId) {
        pauseRequested.add(taskId);
        log.info("已标记暂停 aigen 任务: taskId={}", taskId);
    }

    public boolean isPauseRequested(Long taskId) {
        return pauseRequested.contains(taskId);
    }

    public void clearPauseRequest(Long taskId) {
        pauseRequested.remove(taskId);
    }

    public synchronized void tryStartNext() {
        int max = Math.max(1, aigenProperties.getMaxConcurrentTasks());
        int runningLike = countRunningInDb();
        int occupied = Math.max(runningLike, activeTaskIds.size());
        int slots = max - occupied;
        if (slots <= 0) {
            log.debug("aigen 无空闲槽位: occupied={}, max={}", occupied, max);
            return;
        }

        List<AigenTaskEntity> pending = aigenTaskMapper.selectList(
                new LambdaQueryWrapper<AigenTaskEntity>()
                        .eq(AigenTaskEntity::getStatus, AigenTaskStatus.PENDING.name())
                        .orderByAsc(AigenTaskEntity::getCreatedAt)
                        .last("LIMIT " + Math.max(slots * 2, 4))
        );

        int started = 0;
        for (AigenTaskEntity task : pending) {
            if (started >= slots) {
                break;
            }
            Long id = task.getId();
            if (id == null) {
                continue;
            }
            if (!activeTaskIds.add(id)) {
                continue;
            }
            log.info("调度 aigen 任务: taskId={}, slot={}/{}", id, started + 1, slots);
            try {
                asyncRunner.runAsync(id);
                started++;
            } catch (Exception e) {
                activeTaskIds.remove(id);
                log.error("启动 aigen 异步任务失败: taskId={}", id, e);
            }
        }
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
