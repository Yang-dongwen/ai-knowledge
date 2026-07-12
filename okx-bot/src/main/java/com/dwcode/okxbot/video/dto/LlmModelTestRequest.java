package com.dwcode.okxbot.video.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 测试 LLM 模型是否可用。
 */
@Data
public class LlmModelTestRequest {

    /** 供应商标识，如 nvidia */
    @NotBlank(message = "provider 不能为空")
    private String provider;

    /** 模型 ID，如 deepseek-ai/deepseek-v4-flash */
    @NotBlank(message = "model 不能为空")
    private String model;
}
