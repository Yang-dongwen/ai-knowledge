package com.dwcode.okxbot.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发送消息请求。
 */
@Data
public class ChatRequest {

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 32000, message = "消息内容最多 32000 字")
    private String message;

    /** 会话ID，新对话时可为空 */
    private Long conversationId;

    /** 供应商标识，不传则使用默认供应商 */
    private String provider;

    /** 模型ID，不传则使用供应商默认模型 */
    private String model;

    /** 本次温度覆盖（可选）；否则用会话设置 / 默认 0.7 */
    private Double temperature;

    /** 本次 max_tokens 覆盖（可选）；否则用会话设置 / 默认 2000 */
    private Integer maxTokens;

    /**
     * 新会话时可选写入 system prompt（已有会话不因 null 清空）。
     * 传空串表示显式使用默认提示。
     */
    private String systemPrompt;

    /**
     * 是否启用 Agent 模式（PR-1：可调用只读工具查询任务/模型）。
     * false/null 时行为与纯聊天一致。
     */
    private Boolean agentMode;
}