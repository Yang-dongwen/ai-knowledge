package com.dwcode.okxbot.chat.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新会话：标题 / 模型 / 生成参数 / 系统提示（字段均可选，只更新非 null）。
 */
@Data
public class UpdateConversationRequest {

    @Size(max = 100, message = "标题最多 100 字")
    private String title;

    private String provider;

    private String model;

    @DecimalMin(value = "0.0", message = "temperature 不能小于 0")
    @DecimalMax(value = "2.0", message = "temperature 不能大于 2")
    private Double temperature;

    @Min(value = 64, message = "maxTokens 不能小于 64")
    @Max(value = 16000, message = "maxTokens 不能大于 16000")
    private Integer maxTokens;

    /** 传空字符串可清空为默认 system prompt */
    @Size(max = 8000, message = "systemPrompt 最多 8000 字")
    private String systemPrompt;

    /**
     * 是否显式清空 systemPrompt（true 时写 null 回默认）。
     * 与 systemPrompt="" 等价，便于前端区分「未改」与「清空」。
     */
    private Boolean clearSystemPrompt;
}
