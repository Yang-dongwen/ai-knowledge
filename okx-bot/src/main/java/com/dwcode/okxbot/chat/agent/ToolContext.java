package com.dwcode.okxbot.chat.agent;

import lombok.Builder;
import lombok.Value;

/**
 * 工具执行上下文（强制携带当前用户，禁止跨用户）。
 */
@Value
@Builder
public class ToolContext {
    Long userId;
    Long conversationId;
    String streamId;
}
