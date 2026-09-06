package com.dwcode.okxbot.common.task;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 单模块任务槽位：内存 active + 协作式 pause/cancel。
 * <p>
 * 每个 Scheduler 持有自己的实例（不要做成全局 Bean），避免 video/aigen 抢同一组槽。
 * 查 PENDING、孤儿落库、每用户上限仍由模块负责。
 */
@Slf4j
public final class TaskSlotKernel {

    private final String module;
    private final Set<Long> activeTaskIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> cancelRequested = ConcurrentHashMap.newKeySet();
    private final Set<Long> pauseRequested = ConcurrentHashMap.newKeySet();

    public TaskSlotKernel(String module) {
        this.module = module == null || module.isBlank() ? "task" : module;
    }

    public void markRunning(Long taskId) {
        if (taskId != null) {
            activeTaskIds.add(taskId);
        }
    }

    /**
     * 释放槽位并清协作标记；不自动调度下一单（由 Scheduler {@code tryStartNext}）。
     */
    public void release(Long taskId) {
        if (taskId == null) {
            return;
        }
        activeTaskIds.remove(taskId);
        cancelRequested.remove(taskId);
        pauseRequested.remove(taskId);
    }

    public boolean isActive(Long taskId) {
        return taskId != null && activeTaskIds.contains(taskId);
    }

    public int activeCount() {
        return activeTaskIds.size();
    }

    /** 只读快照，供模块按 user 再过滤。 */
    public Set<Long> activeIds() {
        return Set.copyOf(activeTaskIds);
    }

    public int occupied(int runningLikeInDb) {
        return Math.max(Math.max(0, runningLikeInDb), activeTaskIds.size());
    }

    public int freeSlots(int maxConcurrent, int runningLikeInDb) {
        int max = Math.max(1, maxConcurrent);
        return Math.max(0, max - occupied(runningLikeInDb));
    }

    /**
     * 拉取 PENDING 时的 LIMIT 下限（无每用户过滤时够用）。
     */
    public static int pendingFetchLimit(int slots) {
        return Math.max(slots * 2, 4);
    }

    /**
     * 有每用户上限时多拉几条，避免队头被同一用户占满。
     */
    public static int pendingFetchLimitPerUser(int slots) {
        return Math.max(slots * 4, 8);
    }

    public void requestCancel(Long taskId) {
        if (taskId != null) {
            cancelRequested.add(taskId);
            log.info("已标记取消 {} 任务: taskId={}", module, taskId);
        }
    }

    public boolean isCancelRequested(Long taskId) {
        return taskId != null && cancelRequested.contains(taskId);
    }

    public void clearCancelRequest(Long taskId) {
        if (taskId != null) {
            cancelRequested.remove(taskId);
        }
    }

    public void requestPause(Long taskId) {
        if (taskId != null) {
            pauseRequested.add(taskId);
            log.info("已标记暂停 {} 任务: taskId={}", module, taskId);
        }
    }

    public boolean isPauseRequested(Long taskId) {
        return taskId != null && pauseRequested.contains(taskId);
    }

    public void clearPauseRequest(Long taskId) {
        if (taskId != null) {
            pauseRequested.remove(taskId);
        }
    }

    /**
     * 在空闲槽位下按列表顺序占槽并启动。{@code accept} 为 null 视为全部通过。
     *
     * @return 成功调用 starter 的次数
     */
    public synchronized <T> int startPending(int maxConcurrent,
                                             int runningLikeInDb,
                                             List<T> pending,
                                             Function<T, Long> idOf,
                                             Predicate<T> accept,
                                             Consumer<T> starter) {
        int slots = freeSlots(maxConcurrent, runningLikeInDb);
        if (slots <= 0) {
            log.debug("{} 无空闲槽位: occupied={}, max={}",
                    module, occupied(runningLikeInDb), Math.max(1, maxConcurrent));
            return 0;
        }
        if (pending == null || pending.isEmpty() || idOf == null || starter == null) {
            return 0;
        }
        int started = 0;
        for (T task : pending) {
            if (started >= slots) {
                break;
            }
            if (task == null) {
                continue;
            }
            Long id = idOf.apply(task);
            if (id == null) {
                continue;
            }
            if (accept != null && !accept.test(task)) {
                continue;
            }
            if (!activeTaskIds.add(id)) {
                continue;
            }
            try {
                starter.accept(task);
                started++;
                log.info("调度 {} 任务: taskId={}, slot={}/{}", module, id, started, slots);
            } catch (RuntimeException e) {
                activeTaskIds.remove(id);
                log.error("启动 {} 异步任务失败: taskId={}", module, id, e);
            }
        }
        return started;
    }
}
