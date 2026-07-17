package com.dwcode.okxbot.chat.stream;

import lombok.Getter;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次 SSE 流式生成的可取消句柄。
 */
@Getter
public class ChatStreamHandle {

    private final String streamId;
    private final Long userId;
    private final Long conversationId;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public ChatStreamHandle(String streamId, Long userId, Long conversationId) {
        this.streamId = streamId;
        this.userId = userId;
        this.conversationId = conversationId;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** @return true 表示首次取消 */
    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }
}
