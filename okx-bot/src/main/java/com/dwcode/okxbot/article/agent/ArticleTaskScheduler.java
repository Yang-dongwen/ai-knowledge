package com.dwcode.okxbot.article.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.article.config.ArticleProperties;
import com.dwcode.okxbot.article.entity.ArticleTaskEntity;
import com.dwcode.okxbot.article.enums.ArticleTaskStatus;
import com.dwcode.okxbot.article.event.ArticleTaskEventPublisher;
import com.dwcode.okxbot.article.mapper.ArticleTaskMapper;
import com.dwcode.okxbot.common.task.TaskSlotKernel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文章任务调度：全局槽 + 每用户上限 + 孤儿恢复。
 */
@Slf4j
@Component
public class ArticleTaskScheduler {

    private final ArticleTaskMapper taskMapper;
    private final ArticleTaskAsyncRunner asyncRunner;
    private final ArticleProperties properties;
    private final ArticleTaskEventPublisher eventPublisher;
    private final TaskSlotKernel slots = new TaskSlotKernel("article");

    public ArticleTaskScheduler(ArticleTaskMapper taskMapper,
                                @Lazy ArticleTaskAsyncRunner asyncRunner,
                                ArticleProperties properties,
                                ArticleTaskEventPublisher eventPublisher) {
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
        List<ArticleTaskEntity> orphans = taskMapper.selectList(
                new LambdaQueryWrapper<ArticleTaskEntity>()
                        .in(ArticleTaskEntity::getStatus,
                                ArticleTaskStatus.RESOLVING.name(),
                                ArticleTaskStatus.FETCHING.name(),
                                ArticleTaskStatus.EXTRACTING.name(),
                                ArticleTaskStatus.LLM_CORE.name(),
                                ArticleTaskStatus.LLM_REWRITE.name())
        );
        if (orphans.isEmpty()) {
            tryStartNext();
            return;
        }
        log.warn("发现 {} 个中断的 article 进行中任务，标记 FAILED", orphans.size());
        for (ArticleTaskEntity t : orphans) {
            t.setStatus(ArticleTaskStatus.FAILED.name());
            t.setCurrentStep("服务重启，任务中断");
            t.setErrorCode("PIPELINE_ERROR");
            t.setErrorMessage("服务重启导致任务中断，请点击重试");
            t.setPasteResume(0);
            t.setFinishedAt(LocalDateTime.now());
            t.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(t);
            eventPublisher.publishEntity(t, ArticleTaskEventPublisher.TYPE_STATUS);
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

    public boolean isActive(Long taskId) {
        return slots.isActive(taskId);
    }

    public synchronized void tryStartNext() {
        if (!properties.isEnabled()) {
            return;
        }
        int max = Math.max(1, properties.getMaxConcurrentTasks());
        int runningLike = countRunningInDb(null);
        int slotsFree = slots.freeSlots(max, runningLike);
        if (slotsFree <= 0) {
            return;
        }

        List<ArticleTaskEntity> pending = taskMapper.selectList(
                new LambdaQueryWrapper<ArticleTaskEntity>()
                        .eq(ArticleTaskEntity::getStatus, ArticleTaskStatus.PENDING.name())
                        .orderByAsc(ArticleTaskEntity::getCreatedAt)
                        .last("LIMIT " + TaskSlotKernel.pendingFetchLimitPerUser(slotsFree))
        );

        int perUser = Math.max(1, properties.getMaxConcurrentTasksPerUser());
        slots.startPending(max, runningLike, pending,
                ArticleTaskEntity::getId,
                task -> acceptForUser(task, perUser),
                t -> asyncRunner.runAsync(t.getId()));
    }

    private boolean acceptForUser(ArticleTaskEntity task, int perUser) {
        Long userId = task.getUserId();
        if (task.getId() == null || userId == null) {
            return false;
        }
        int userRunning = countRunningInDb(userId);
        for (Long activeId : slots.activeIds()) {
            ArticleTaskEntity a = taskMapper.selectById(activeId);
            if (a != null && userId.equals(a.getUserId())
                    && ArticleTaskStatus.PENDING.name().equals(a.getStatus())) {
                userRunning++;
            }
        }
        return userRunning < perUser;
    }

    /**
     * @param userId null=全局 running 计数
     */
    public int countRunningInDb(Long userId) {
        LambdaQueryWrapper<ArticleTaskEntity> q = new LambdaQueryWrapper<ArticleTaskEntity>()
                .in(ArticleTaskEntity::getStatus,
                        ArticleTaskStatus.RESOLVING.name(),
                        ArticleTaskStatus.FETCHING.name(),
                        ArticleTaskStatus.EXTRACTING.name(),
                        ArticleTaskStatus.LLM_CORE.name(),
                        ArticleTaskStatus.LLM_REWRITE.name());
        if (userId != null) {
            q.eq(ArticleTaskEntity::getUserId, userId);
        }
        Long cnt = taskMapper.selectCount(q);
        return cnt == null ? 0 : cnt.intValue();
    }

    /**
     * 用户进行中 + 排队任务数（创建时 429 用）。
     */
    public int countUserInFlight(Long userId) {
        if (userId == null) {
            return 0;
        }
        Long cnt = taskMapper.selectCount(
                new LambdaQueryWrapper<ArticleTaskEntity>()
                        .eq(ArticleTaskEntity::getUserId, userId)
                        .in(ArticleTaskEntity::getStatus,
                                ArticleTaskStatus.PENDING.name(),
                                ArticleTaskStatus.RESOLVING.name(),
                                ArticleTaskStatus.FETCHING.name(),
                                ArticleTaskStatus.EXTRACTING.name(),
                                ArticleTaskStatus.LLM_CORE.name(),
                                ArticleTaskStatus.LLM_REWRITE.name())
        );
        return cnt == null ? 0 : cnt.intValue();
    }
}
