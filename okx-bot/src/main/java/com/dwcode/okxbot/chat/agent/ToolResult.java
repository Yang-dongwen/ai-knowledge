package com.dwcode.okxbot.chat.agent;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 工具执行结果：给人看的 message + 给前端卡片的 data/ui。
 */
@Data
@Builder
public class ToolResult {

    private boolean ok;
    private String code;
    private String message;
    /** 结构化数据，前端渲染用 */
    private Object data;
    /**
     * UI 提示，例如：
     * { "type": "task_list", "payload": {...} }
     * { "type": "task_status", "payload": {...} }
     * { "type": "model_list", "payload": {...} }
     */
    private Map<String, Object> ui;

    public static ToolResult success(String message, Object data, Map<String, Object> ui) {
        return ToolResult.builder().ok(true).message(message).data(data).ui(ui).build();
    }

    public static ToolResult fail(String code, String message) {
        return ToolResult.builder().ok(false).code(code).message(message).build();
    }
}
