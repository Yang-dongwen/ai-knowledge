package com.dwcode.okxbot.chat.stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活跃聊天流注册表：支持按 streamId / 会话 取消后端生成。
 */
@Slf4j
@Component
public class ChatStreamRegistry {

    private final ConcurrentHashMap<String, ChatStreamHandle> byStreamId = new ConcurrentHashMap<>();
    /** userId:conversationId -> streamId */
    private final ConcurrentHashMap<String, String> activeByUserConv = new ConcurrentHashMap<>();

    public ChatStreamHandle register(Long userId, Long conversationId) {
        String key = userConvKey(userId, conversationId);
        // 同一用户同一会话只允许一条活跃流：取消旧的
        String oldId = activeByUserConv.get(key);
        if (oldId != null) {
            ChatStreamHandle old = byStreamId.get(oldId);
            if (old != null) {
                old.cancel();
                log.info("取消同会话旧流: streamId={}, conv={}", oldId, conversationId);
            }
            byStreamId.remove(oldId);
        }

        String streamId = UUID.randomUUID().toString().replace("-", "");
        ChatStreamHandle handle = new ChatStreamHandle(streamId, userId, conversationId);
        byStreamId.put(streamId, handle);
        activeByUserConv.put(key, streamId);
        log.debug("注册聊天流: streamId={}, userId={}, conv={}", streamId, userId, conversationId);
        return handle;
    }

    public void unregister(ChatStreamHandle handle) {
        if (handle == null) {
            return;
        }
        byStreamId.remove(handle.getStreamId(), handle);
        String key = userConvKey(handle.getUserId(), handle.getConversationId());
        activeByUserConv.compute(key, (k, v) ->
                handle.getStreamId().equals(v) ? null : v);
    }

    /**
     * 取消流。streamId 优先；否则按 conversationId + 当前用户匹配。
     *
     * @return true 找到并取消
     */
    public boolean cancel(Long userId, String streamId, Long conversationId) {
        if (streamId != null && !streamId.isBlank()) {
            ChatStreamHandle h = byStreamId.get(streamId.trim());
            if (h == null) {
                return false;
            }
            if (!h.getUserId().equals(userId)) {
                return false;
            }
            boolean first = h.cancel();
            log.info("按 streamId 取消聊天流: streamId={}, first={}", streamId, first);
            return true;
        }
        if (conversationId != null) {
            String key = userConvKey(userId, conversationId);
            String id = activeByUserConv.get(key);
            if (id == null) {
                return false;
            }
            ChatStreamHandle h = byStreamId.get(id);
            if (h == null) {
                return false;
            }
            boolean first = h.cancel();
            log.info("按 conversationId 取消聊天流: conv={}, streamId={}, first={}",
                    conversationId, id, first);
            return true;
        }
        return false;
    }

    private static String userConvKey(Long userId, Long conversationId) {
        return userId + ":" + conversationId;
    }

    public Map<String, ChatStreamHandle> snapshot() {
        return Map.copyOf(byStreamId);
    }
}
