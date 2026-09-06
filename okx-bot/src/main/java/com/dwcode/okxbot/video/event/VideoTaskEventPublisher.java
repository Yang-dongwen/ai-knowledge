package com.dwcode.okxbot.video.event;

import com.dwcode.okxbot.common.sse.SseEventTypes;
import com.dwcode.okxbot.common.sse.UserSseHub;
import com.dwcode.okxbot.storage.ObjectKeyBuilder;
import com.dwcode.okxbot.storage.ObjectStoragePort;
import com.dwcode.okxbot.video.entity.VideoTaskEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 视频任务 SSE。连接管理在 {@link UserSseHub}（channel=video）。
 * <p>单机内存；多实例后续可在 Hub 后换 Redis Pub/Sub。
 */
@Component
@RequiredArgsConstructor
public class VideoTaskEventPublisher {

    public static final String CHANNEL = "video";
    public static final String TYPE_CONNECTED = SseEventTypes.CONNECTED;
    public static final String TYPE_PING = SseEventTypes.PING;
    public static final String TYPE_CREATED = SseEventTypes.CREATED;
    public static final String TYPE_STATUS = SseEventTypes.STATUS;
    public static final String TYPE_DELETED = SseEventTypes.DELETED;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserSseHub sseHub;
    private final ObjectStoragePort objectStorage;

    public SseEmitter subscribe(Long userId) {
        return sseHub.subscribe(CHANNEL, userId);
    }

    public void publishEntity(VideoTaskEntity entity, String type) {
        if (entity == null || entity.getUserId() == null || entity.getId() == null) {
            return;
        }
        Map<String, Object> data = toLightData(entity);
        publish(entity.getUserId(), type, String.valueOf(entity.getId()), data);
    }

    public void publishDeleted(Long userId, Long taskId) {
        if (userId == null || taskId == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", String.valueOf(taskId));
        publish(userId, TYPE_DELETED, String.valueOf(taskId), data);
    }

    public void publish(Long userId, String type, String taskId, Map<String, Object> data) {
        sseHub.publish(CHANNEL, userId, type, taskId, data, false);
    }

    private Map<String, Object> toLightData(VideoTaskEntity e) {
        boolean videoAvailable = false;
        String vp = e.getVideoPath();
        if (vp != null && !vp.isBlank()) {
            if (ObjectKeyBuilder.looksLikeLocalAbsolutePath(vp)) {
                try {
                    videoAvailable = Files.isRegularFile(Path.of(vp));
                } catch (Exception ignored) {
                    videoAvailable = false;
                }
            } else {
                try {
                    videoAvailable = objectStorage.exists(vp);
                    if (!videoAvailable && vp.contains("video.") && !vp.contains("browser")) {
                        int slash = vp.lastIndexOf('/');
                        String browserKey = (slash >= 0 ? vp.substring(0, slash + 1) : "") + "video.browser.mp4";
                        videoAvailable = objectStorage.exists(browserKey);
                    }
                } catch (Exception ignored) {
                    videoAvailable = true;
                }
            }
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("taskId", String.valueOf(e.getId()));
        d.put("status", e.getStatus());
        d.put("url", e.getSourceUrl());
        d.put("title", e.getTitle());
        d.put("platform", e.getPlatform());
        d.put("llmProvider", e.getLlmProvider());
        d.put("llmModel", e.getLlmModel());
        d.put("understandingMode", e.getUnderstandingMode());
        d.put("omniProvider", e.getOmniProvider());
        d.put("omniModel", e.getOmniModel());
        d.put("currentStep", e.getCurrentStep());
        d.put("errorMessage", e.getErrorMessage());
        d.put("durationSeconds", e.getDurationSeconds());
        d.put("videoAvailable", videoAvailable);
        d.put("createdAt", formatTime(e.getCreatedAt()));
        d.put("startedAt", formatTime(e.getStartedAt()));
        d.put("finishedAt", formatTime(e.getFinishedAt()));
        d.put("downloadDurationMs", e.getDownloadDurationMs());
        d.put("transcribeDurationMs", e.getTranscribeDurationMs());
        d.put("understandDurationMs", e.getUnderstandDurationMs());
        d.put("summarizeDurationMs", e.getSummarizeDurationMs());
        d.put("totalDurationMs", e.getTotalDurationMs());
        d.put("degraded", e.getDegraded() != null && e.getDegraded() == 1);
        d.put("degradeReason", e.getDegradeReason());
        return d;
    }

    private static String formatTime(LocalDateTime t) {
        return t == null ? null : DT_FMT.format(t);
    }
}
