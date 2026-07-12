package com.dwcode.okxbot.video.dto;

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

    /** 是否启用，默认 true */
    private Boolean enabled = true;

    /** 排序，默认 0 */
    private Integer sortOrder = 0;

    /** 备注 */
    private String remark;
}
