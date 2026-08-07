package com.dwcode.okxbot.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑某条用户消息并从此处重发：截断该消息之后的所有消息，更新内容后流式生成。
 */
@Data
public class EditResendRequest {

    @NotNull(message = "会话 ID 不能为空")
    private Long conversationId;

    /** 要编辑的用户消息 ID */
    @NotNull(message = "消息 ID 不能为空")
    private Long messageId;

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 32000, message = "消息内容最多 32000 字")
    private String message;

    private String provider;

    private String model;

    private Double temperature;

    private Integer maxTokens;
}
