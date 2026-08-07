package com.dwcode.okxbot.video.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 失败/暂停任务重试请求（可重新指定 LLM）。
 * <p>字符串上限对齐 {@code video_task} 列宽。
 */
@Data
public class VideoRetryRequest {

    /** 可选，覆盖原任务 LLM 供应商；对齐 llm_provider VARCHAR(64) */
    @Size(max = 64, message = "llmProvider 最长 64 字符")
    private String llmProvider;

    /** 可选，覆盖原任务 LLM 模型；对齐 llm_model VARCHAR(128) */
    @Size(max = 128, message = "llmModel 最长 128 字符")
    private String llmModel;

    /** 可选，覆盖理解模式；对齐 understanding_mode VARCHAR(32) */
    @Size(max = 32, message = "understandingMode 最长 32 字符")
    private String understandingMode;

    /** 对齐 omni_provider VARCHAR(64) */
    @Size(max = 64, message = "omniProvider 最长 64 字符")
    private String omniProvider;
    /** 对齐 omni_model VARCHAR(128) */
    @Size(max = 128, message = "omniModel 最长 128 字符")
    private String omniModel;
}
