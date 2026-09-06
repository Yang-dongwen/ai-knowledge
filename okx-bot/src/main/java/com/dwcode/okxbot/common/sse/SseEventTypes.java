package com.dwcode.okxbot.common.sse;

/**
 * 任务 SSE 事件名（与前端约定）。四套工具共用同一套 type 字符串。
 */
public final class SseEventTypes {

    public static final String CONNECTED = "connected";
    public static final String PING = "ping";
    public static final String CREATED = "task.created";
    public static final String STATUS = "task.status";
    public static final String DELETED = "task.deleted";

    private SseEventTypes() {
    }
}
