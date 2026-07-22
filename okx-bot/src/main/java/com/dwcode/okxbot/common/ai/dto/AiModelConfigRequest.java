package com.dwcode.okxbot.common.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 新增/更新 LLM 模型配置。
 */
@Data
public class AiModelConfigRequest {

    /** 供应商标识，如 nvidia */
    @NotBlank(message = "provider 不能为空")
    private String provider;

    /** API 模型 ID */
    @NotBlank(message = "modelId 不能为空")
    private String modelId;

    /** 展示名称 */
    @NotBlank(message = "modelName 不能为空")
    private String modelName;

    /**
     * 能力：chat | image，默认 chat。
     */
    private String capability = "chat";

    /** 生图 invoke URL（image 必填） */
    private String invokeUrl;

    /** 生图默认步数 */
    private Integer defaultSteps;

    /** 生图最大步数 */
    private Integer maxSteps;

    /**
     * 生图协议：nvidia-flux | nvidia-qwen | nvidia-openai-images（可选，空则自动推断）
     */
    private String protocol;

    /** 是否启用，默认 true */
    private Boolean enabled = true;

    /** 排序，默认 0 */
    private Integer sortOrder = 0;

    /** 备注 */
    private String remark;
}
