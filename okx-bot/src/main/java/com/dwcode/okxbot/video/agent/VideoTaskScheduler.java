package com.dwcode.okxbot.video.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.video.entity.VideoTaskEntity;
import com.dwcode.okxbot.video.enums.VideoTaskStatus;
import com.dwcode.okxbot.video.mapper.VideoTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 视频任务调度：
 * <ul>
 *   <li>控制并发（与异步线程池大致对齐）</li>
 *   <li>PENDING 排队，有空闲槽位时启动</li>
 *   <li>暂停请求标记，供流水线协作式中断</li>
 * </ul>
 */
@Slf4j
@Component
public class VideoTaskScheduler {

    /** 与 VideoAsyncConfig 核心线程数对齐 */
    public static final int MAX_CONCURRENT = 2;

    private final VideoTaskMapper videoTaskMapper;
    private final VideoTaskAsyncRunner asyncRunner;

    public VideoTaskScheduler(VideoTaskMapper videoTaskMapper,
                              @Lazy VideoTaskAsyncRunner asyncRunner) {
        this.videoTaskMapper = videoTaskMapper;
        this.asyncRunner = asyncRunner;
    }

    /** 已调度或正在执行的任务，防止重复 runAsync */
    private final Set<Long> activeTaskIds = ConcurrentHashMap.newKeySet();

    /** 用户请求暂停的任务 */
    private final Set<Long> pauseRequested = ConcurrentHashMap.newKeySet();

    /**
     * 任务进入 PENDING 后调用，尝试启动排队中的任务。
     */
    public void notifyPending() {
        tryStartNext();
    }

    /**
     * 流水线真正开始执行时标记（幂等）。
     */
    public void markRunning(Long taskId) {
        activeTaskIds.add(taskId);
    }

    /**
     * 流水线结束（成功/失败/暂停）后释放槽位，并启动下一批排队任务。
     */
    public void markFinished(Long taskId) {
        activeTaskIds.remove(taskId);
        pauseRequested.remove(taskId);
        tryStartNext();
    }

    public void requestPause(Long taskId) {
        pauseRequested.add(taskId);
        log.info("已标记暂停: taskId={}", taskId);
    }

    public boolean isPauseRequested(Long taskId) {
        return pauseRequested.contains(taskId);
    }

    public void clearPauseRequest(Long taskId) {
        pauseRequested.remove(taskId);
    }

    /**
     * 在空闲槽位下启动最早的 PENDING 任务。
     */
    public synchronized void tryStartNext() {
        int runningLike = countRunningInDb();
        // active 可能略大于 DB（刚启动尚未更新状态），取较大值估占用
        int occupied = Math.max(runningLike, activeTaskIds.size());
        int slots = MAX_CONCURRENT - occupied;
        if (slots <= 0) {
            log.debug("无空闲槽位: occupied={}, max={}", occupied, MAX_CONCURRENT);
            return;
        }

        List<VideoTaskEntity> pending = videoTaskMapper.selectList(
                new LambdaQueryWrapper<VideoTaskEntity>()
                        .eq(VideoTaskEntity::getStatus, VideoTaskStatus.PENDING.name())
                        .orderByAsc(VideoTaskEntity::getCreatedAt)
                        .last("LIMIT " + Math.max(slots * 2, 4))
        );

        int started = 0;
        for (VideoTaskEntity task : pending) {
            if (started >= slots) {
                break;
            }
            Long id = task.getId();
            if (id == null) {
                continue;
            }
            if (!activeTaskIds.add(id)) {
                // 已在执行/已调度
                continue;
            }
            log.info("调度启动排队任务: taskId={}, slot={}/{}", id, started + 1, slots);
            asyncRunner.runAsync(id);
            started++;
        }
    }

    private int countRunningInDb() {
        Long cnt = videoTaskMapper.selectCount(
                new LambdaQueryWrapper<VideoTaskEntity>()
                        .in(VideoTaskEntity::getStatus,
                                VideoTaskStatus.DOWNLOADING.name(),
                                VideoTaskStatus.TRANSCRIBING.name(),
                                VideoTaskStatus.SUMMARIZING.name())
        );
        return cnt == null ? 0 : cnt.intValue();
    }
}
