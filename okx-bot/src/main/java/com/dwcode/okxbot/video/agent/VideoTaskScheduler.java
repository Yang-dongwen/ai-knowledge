package com.dwcode.okxbot.video.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.common.task.TaskSlotKernel;
import com.dwcode.okxbot.video.entity.VideoTaskEntity;
import com.dwcode.okxbot.video.enums.VideoTaskStatus;
import com.dwcode.okxbot.video.mapper.VideoTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 视频任务调度：并发槽位 + PENDING FIFO + 暂停协作标记。
 */
@Slf4j
@Component
public class VideoTaskScheduler {

    /** 与 VideoAsyncConfig 核心线程数对齐 */
    public static final int MAX_CONCURRENT = 2;

    private final VideoTaskMapper videoTaskMapper;
    private final VideoTaskAsyncRunner asyncRunner;
    private final TaskSlotKernel slots = new TaskSlotKernel("video");

    public VideoTaskScheduler(VideoTaskMapper videoTaskMapper,
                              @Lazy VideoTaskAsyncRunner asyncRunner) {
        this.videoTaskMapper = videoTaskMapper;
        this.asyncRunner = asyncRunner;
    }

    /**
     * 进程重启后 DB 可能残留进行中状态，占满并发槽；标记 FAILED 并继续排队。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanRunningTasks() {
        List<VideoTaskEntity> orphans = videoTaskMapper.selectList(
                new LambdaQueryWrapper<VideoTaskEntity>()
                        .in(VideoTaskEntity::getStatus,
                                VideoTaskStatus.DOWNLOADING.name(),
                                VideoTaskStatus.TRANSCRIBING.name(),
                                VideoTaskStatus.UNDERSTANDING.name(),
                                VideoTaskStatus.SUMMARIZING.name())
        );
        if (orphans.isEmpty()) {
            tryStartNext();
            return;
        }
        log.warn("发现 {} 个中断的 video 进行中任务，标记 FAILED 并调度排队", orphans.size());
        LocalDateTime now = LocalDateTime.now();
        for (VideoTaskEntity t : orphans) {
            t.setStatus(VideoTaskStatus.FAILED.name());
            t.setCurrentStep("服务重启，任务中断");
            t.setErrorMessage("服务重启导致任务中断，请点击重试");
            t.setFinishedAt(now);
            t.setUpdatedAt(now);
            videoTaskMapper.updateById(t);
            slots.release(t.getId());
        }
        tryStartNext();
    }

    public void notifyPending() {
        tryStartNext();
    }

    public void markRunning(Long taskId) {
        slots.markRunning(taskId);
    }

    public void markFinished(Long taskId) {
        slots.release(taskId);
        tryStartNext();
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
        int runningLike = countRunningInDb();
        int slotsFree = slots.freeSlots(MAX_CONCURRENT, runningLike);
        if (slotsFree <= 0) {
            return;
        }
        List<VideoTaskEntity> pending = videoTaskMapper.selectList(
                new LambdaQueryWrapper<VideoTaskEntity>()
                        .eq(VideoTaskEntity::getStatus, VideoTaskStatus.PENDING.name())
                        .orderByAsc(VideoTaskEntity::getCreatedAt)
                        .last("LIMIT " + TaskSlotKernel.pendingFetchLimit(slotsFree))
        );
        slots.startPending(MAX_CONCURRENT, runningLike, pending,
                VideoTaskEntity::getId, null, t -> asyncRunner.runAsync(t.getId()));
    }

    private int countRunningInDb() {
        Long cnt = videoTaskMapper.selectCount(
                new LambdaQueryWrapper<VideoTaskEntity>()
                        .in(VideoTaskEntity::getStatus,
                                VideoTaskStatus.DOWNLOADING.name(),
                                VideoTaskStatus.TRANSCRIBING.name(),
                                VideoTaskStatus.UNDERSTANDING.name(),
                                VideoTaskStatus.SUMMARIZING.name())
        );
        return cnt == null ? 0 : cnt.intValue();
    }
}
