package com.dwcode.okxbot.imggen.event;

import com.dwcode.okxbot.common.sse.SseEventTypes;
import com.dwcode.okxbot.common.sse.UserSseHub;
import com.dwcode.okxbot.imggen.entity.ImgGenTaskEntity;
import com.dwcode.okxbot.storage.ObjectKeyBuilder;
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
 * 文生图任务 SSE。连接管理在 {@link UserSseHub}（channel=imggen）。
 */
@Component
@RequiredArgsConstructor
public class ImgGenTaskEventPublisher {

    public static final String CHANNEL = "imggen";
    public static final String TYPE_CONNECTED = SseEventTypes.CONNECTED;
    public static final String TYPE_PING = SseEventTypes.PING;
    public static final String TYPE_CREATED = SseEventTypes.CREATED;
    public static final String TYPE_STATUS = SseEventTypes.STATUS;
    public static final String TYPE_DELETED = SseEventTypes.DELETED;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserSseHub sseHub;

    public SseEmitter subscribe(Long userId) {
        return sseHub.subscribe(CHANNEL, userId);
    }

    public void publishEntity(ImgGenTaskEntity entity, String type) {
        if (entity == null || entity.getUserId() == null || entity.getId() == null) {
            return;
        }
        publish(entity.getUserId(), type, String.valueOf(entity.getId()), toLightData(entity));
    }

    public void publishDeleted(Long userId, Long taskId) {
        if (userId == null || taskId == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", String.valueOf(taskId));
        data.put("taskId", String.valueOf(taskId));
        publish(userId, TYPE_DELETED, String.valueOf(taskId), data);
    }

    public void publish(Long userId, String type, String taskId, Map<String, Object> data) {
        sseHub.publish(CHANNEL, userId, type, taskId, data, true);
    }

    private Map<String, Object> toLightData(ImgGenTaskEntity e) {
        boolean outputAvailable = false;
        String cp = e.getCoverPath();
        if (cp != null && !cp.isBlank()) {
            if (ObjectKeyBuilder.looksLikeLocalAbsolutePath(cp)) {
                try {
                    outputAvailable = Files.isRegularFile(Path.of(cp));
                } catch (Exception ignored) {
                    outputAvailable = false;
                }
            } else {
                outputAvailable = true;
            }
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", String.valueOf(e.getId()));
        d.put("taskId", String.valueOf(e.getId()));
        d.put("title", e.getTitle());
        d.put("prompt", e.getPrompt());
        d.put("enhancedPrompt", e.getEnhancedPrompt());
        d.put("status", e.getStatus());
        d.put("currentStep", e.getCurrentStep());
        d.put("progress", e.getProgress() != null ? e.getProgress() : 0);
        d.put("aspectRatio", e.getAspectRatio());
        d.put("width", e.getWidth());
        d.put("height", e.getHeight());
        d.put("n", e.getN());
        d.put("model", e.getModel());
        d.put("provider", e.getProvider());
        d.put("enhanceEnabled", e.getEnhanceEnabled() != null && e.getEnhanceEnabled() == 1);
        d.put("llmProvider", e.getLlmProvider());
        d.put("llmModel", e.getLlmModel());
        d.put("errorMessage", e.getErrorMessage() == null ? "" : e.getErrorMessage());
        d.put("outputAvailable", outputAvailable);
        d.put("enhanceDurationMs", e.getEnhanceDurationMs() != null ? e.getEnhanceDurationMs() : 0L);
        d.put("generateDurationMs", e.getGenerateDurationMs() != null ? e.getGenerateDurationMs() : 0L);
        d.put("totalDurationMs", e.getTotalDurationMs() != null ? e.getTotalDurationMs() : 0L);
        d.put("startedAt", formatTime(e.getStartedAt()));
        d.put("finishedAt", formatTime(e.getFinishedAt()));
        d.put("createdAt", formatTime(e.getCreatedAt()));
        d.put("updatedAt", formatTime(e.getUpdatedAt()));
        return d;
    }

    private static String formatTime(LocalDateTime t) {
        return t == null ? null : DT_FMT.format(t);
    }
}
