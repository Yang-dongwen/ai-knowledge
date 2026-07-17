package com.dwcode.okxbot.chat.agent;

import java.util.Map;

/**
 * Agent 工具契约。
 */
public interface AgentTool {

    /** 唯一名称，snake_case */
    String name();

    /** 给模型看的说明（含参数） */
    String description();

    ToolRisk risk();

    /**
     * 执行工具。
     *
     * @param ctx  用户/会话上下文
     * @param args 已解析参数（可为 empty）
     */
    ToolResult execute(ToolContext ctx, Map<String, Object> args);
}
