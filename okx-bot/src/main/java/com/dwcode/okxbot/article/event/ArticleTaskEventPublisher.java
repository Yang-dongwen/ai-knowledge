package com.dwcode.okxbot.article.event;

import com.dwcode.okxbot.article.entity.ArticleTaskEntity;
import com.dwcode.okxbot.common.sse.SseEventTypes;
import com.dwcode.okxbot.common.sse.UserSseHub;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文章任务 SSE。连接管理在 {@link UserSseHub}（channel=article）。
 */
@Component
@RequiredArgsConstructor
public class ArticleTaskEventPublisher {

    public static final String CHANNEL = "article";
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

    public void publishEntity(ArticleTaskEntity entity, String type) {
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

    /**
     * list/SSE 轻量字段：禁止 mainText/core/rewrite。
     */
    public Map<String, Object> toLightData(ArticleTaskEntity e) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", String.valueOf(e.getId()));
        d.put("taskId", String.valueOf(e.getId()));
        d.put("userId", e.getUserId() != null ? String.valueOf(e.getUserId()) : null);
        d.put("sourceUrl", e.getSourceUrl());
        d.put("platform", e.getPlatform());
        d.put("supportLevel", e.getSupportLevel());
        d.put("title", e.getTitle());
        d.put("status", e.getStatus());
        d.put("currentStep", e.getCurrentStep());
        d.put("progress", e.getProgress() != null ? e.getProgress() : 0);
        d.put("inputMode", e.getInputMode());
        d.put("llmProvider", e.getLlmProvider());
        d.put("llmModel", e.getLlmModel());
        d.put("mainTextChars", e.getMainTextChars());
        d.put("degraded", e.getDegraded() != null && e.getDegraded() == 1);
        d.put("errorCode", e.getErrorCode() == null ? "" : e.getErrorCode());
        d.put("errorMessage", e.getErrorMessage() == null ? "" : e.getErrorMessage());
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
