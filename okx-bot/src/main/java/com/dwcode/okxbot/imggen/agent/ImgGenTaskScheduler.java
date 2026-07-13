package com.dwcode.okxbot.imggen.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ImgGenTaskScheduler {

    private final ImgGenTaskMapper taskMapper;
    private final ImgGenTaskAsyncRunner asyncRunner;
    private final ImgGenProperties properties;
    private final ImgGenTaskEventPublisher eventPublisher;

    private final Set<Long> activeTaskIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> cancelRequested = ConcurrentHashMap.newKeySet();
    private final Set<Long> pauseRequested = ConcurrentHashMap.newKeySet();

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
    }

    public boolean isCancelRequested(Long taskId) {
        return cancelRequested.contains(taskId);
    }

    public void clearCancelRequest(Long taskId) {
        cancelRequested.remove(taskId);
    }

    public void requestPause(Long taskId) {
        pauseRequested.add(taskId);
    }

    public boolean isPauseRequested(Long taskId) {
        return pauseRequested.contains(taskId);
    }

    public void clearPauseRequest(Long taskId) {
        pauseRequested.remove(taskId);
    }

    public synchronized void tryStartNext() {
        if (!properties.isEnabled()) {
            return;
        }
        int max = Math.max(1, properties.getMaxConcurrentTasks());
        int runningLike = countRunningInDb();
        int occupied = Math.max(runningLike, activeTaskIds.size());
        int slots = max - occupied;
        if (slots <= 0) {
            return;
        }

        List<ImgGenTaskEntity> pending = taskMapper.selectList(
                new LambdaQueryWrapper<ImgGenTaskEntity>()
                        .eq(ImgGenTaskEntity::getStatus, ImgGenTaskStatus.PENDING.name())
                        .orderByAsc(ImgGenTaskEntity::getCreatedAt)
                        .last("LIMIT " + Math.max(slots * 2, 4))
        );

        int started = 0;
        for (ImgGenTaskEntity task : pending) {
            if (started >= slots) {
                break;
            }
            Long id = task.getId();
            if (id == null || !activeTaskIds.add(id)) {
                continue;
            }
            log.info("调度 imggen 任务: taskId={}", id);
            try {
                asyncRunner.runAsync(id);
                started++;
            } catch (Exception e) {
                activeTaskIds.remove(id);
                log.error("启动 imggen 异步任务失败: taskId={}", id, e);
            }
        }
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
