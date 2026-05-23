package com.dwcode.okxbot.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 发送消息请求。
 */
@Data
public class ChatRequest {

    @NotBlank(message = "消息内容不能为空")
    private String message;

    /** 会话ID，新对话时可为空 */
    private Long conversationId;

    /** 供应商标识，不传则使用默认供应商 */
    private String provider;

    /** 模型ID，不传则使用供应商默认模型 */
    private String model;
}