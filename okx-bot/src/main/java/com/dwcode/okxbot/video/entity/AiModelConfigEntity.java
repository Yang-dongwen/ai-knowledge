package com.dwcode.okxbot.video.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 可配置的 LLM 模型（存库，替代 yml 中的 models 列表）。
 * 供应商 api-key / base-url 仍使用 application.yml 的 ai.providers。
 */
@Data
@TableName("ai_model_config")
public class AiModelConfigEntity {

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 供应商标识，对应 ai.providers 的 key，如 nvidia */
    private String provider;

    /** 调用 API 时的模型 ID，如 deepseek-ai/deepseek-v4-flash */
    private String modelId;

    /** 前端展示名称 */
    private String modelName;

    /** 是否启用 1/0 */
    private Integer enabled;

    /** 排序，越小越靠前 */
    private Integer sortOrder;

    /** 备注 */
    private String remark;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
