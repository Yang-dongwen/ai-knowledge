package com.dwcode.okxbot.imggen.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 独立润色请求：仅返回润色结果，不创建生图任务。
 * 前端写回输入框，由用户确认后再提交任务。
 */
@Data
public class ImgGenEnhanceRequest {

    @NotBlank(message = "prompt 不能为空")
    @Size(max = 4000, message = "prompt 最长 4000 字符")
    private String prompt;

    /** Chat 供应商 key（如 deepseek / openai） */
    private String llmProvider;

    /** Chat 模型 ID */
    private String llmModel;

    /**
     * 可选语言提示：auto / zh / en。
     * 默认 auto：与用户原文同语言。
     */
    private String languageHint;
}
