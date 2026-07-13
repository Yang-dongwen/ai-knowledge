package com.dwcode.okxbot.video.dto;

import lombok.Builder;
import lombok.Data;

/**
 * LLM 模型配置响应。
 */
@Data
@Builder
public class AiModelConfigResponse {
    private String id;
    private String provider;
    /** 供应商显示名（来自 yml） */
    private String providerName;
    private String modelId;
    private String modelName;
    /** chat | image */
    private String capability;
    private String invokeUrl;
    private Integer defaultSteps;
    private Integer maxSteps;
    /** nvidia-flux | nvidia-qwen | nvidia-openai-images */
    private String protocol;
    private Boolean enabled;
    private Integer sortOrder;
    private String remark;
    private String createdAt;
    private String updatedAt;
}
