package com.dwcode.okxbot.chat.dto;

import lombok.Data;

/**
 * 停止当前流式生成。
 * streamId 与 conversationId 至少传一个（优先 streamId）。
 */
@Data
public class StopChatRequest {

    /** 流式 meta 中返回的 streamId */
    private String streamId;

    /** 会话 ID（同用户同会话当前活跃流） */
    private Long conversationId;
}
