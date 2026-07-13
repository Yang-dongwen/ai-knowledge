package com.dwcode.okxbot.aigen.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交 AI 视频生成任务。
 */
@Data
public class AigenCreateRequest {

    @NotBlank(message = "prompt 不能为空")
    @Size(max = 4000, message = "prompt 最长 4000 字符")
    private String prompt;

    /** 模板 ID，默认 knowledge-cards */
    private String templateId;

    private AigenCreateOptions options;
}
