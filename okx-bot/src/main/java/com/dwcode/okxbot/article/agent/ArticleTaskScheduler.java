package com.dwcode.okxbot.article.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.article.config.ArticleProperties;
import com.dwcode.okxbot.article.entity.ArticleTaskEntity;
import com.dwcode.okxbot.article.enums.ArticleTaskStatus;
import com.dwcode.okxbot.article.event.ArticleTaskEventPublisher;
import com.dwcode.okxbot.article.mapper.ArticleTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文章任务调度：全局槽 + 每用户上限 + 孤儿恢复（对齐 ImgGenTaskScheduler）。
 */
@Slf4j
@Component
public class ArticleTaskScheduler {

    private final ArticleTaskMapper taskMapper;
    private final ArticleTaskAsyncRunner asyncRunner;
    private final ArticleProperties properties;
    private final ArticleTaskEventPublisher eventPublisher;

    private final Set<Long> activeTaskIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> cancelRequested = ConcurrentHashMap.newKeySet();
    private final Set<Long> pauseRequested = ConcurrentHashMap.newKeySet();

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

    public boolean isActive(Long taskId) {
        return taskId != null && activeTaskIds.contains(taskId);
    }

    public synchronized void tryStartNext() {
        if (!properties.isEnabled()) {
            return;
        }
        int max = Math.max(1, properties.getMaxConcurrentTasks());
        int perUser = Math.max(1, properties.getMaxConcurrentTasksPerUser());
        int runningLike = countRunningInDb(null);
        int occupied = Math.max(runningLike, activeTaskIds.size());
        int slots = max - occupied;
        if (slots <= 0) {
            return;
        }

        List<ArticleTaskEntity> pending = taskMapper.selectList(
                new LambdaQueryWrapper<ArticleTaskEntity>()
                        .eq(ArticleTaskEntity::getStatus, ArticleTaskStatus.PENDING.name())
                        .orderByAsc(ArticleTaskEntity::getCreatedAt)
                        .last("LIMIT " + Math.max(slots * 4, 8))
        );

        int started = 0;
        for (ArticleTaskEntity task : pending) {
            if (started >= slots) {
                break;
            }
            Long id = task.getId();
            Long userId = task.getUserId();
            if (id == null || userId == null) {
                continue;
            }
            int userRunning = countRunningInDb(userId);
            // 本轮已占用 active 且属该用户的也计入
            for (Long activeId : activeTaskIds) {
                ArticleTaskEntity a = taskMapper.selectById(activeId);
                if (a != null && userId.equals(a.getUserId())) {
                    // 若 DB 已是 running 会重复计；active 仅作下限保护
                    // 简化：若 active 任务 user 匹配且 status 仍 PENDING（刚 add 未更新），加 1
                    if (ArticleTaskStatus.PENDING.name().equals(a.getStatus())) {
                        userRunning++;
                    }
                }
            }
            if (userRunning >= perUser) {
                continue;
            }
            if (!activeTaskIds.add(id)) {
                continue;
            }
            log.info("调度 article 任务: taskId={} userId={}", id, userId);
            try {
                asyncRunner.runAsync(id);
                started++;
            } catch (Exception e) {
                activeTaskIds.remove(id);
                log.error("启动 article 异步任务失败: taskId={}", id, e);
            }
        }
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
