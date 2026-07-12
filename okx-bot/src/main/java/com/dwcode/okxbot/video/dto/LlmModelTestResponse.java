package com.dwcode.okxbot.video.dto;

import lombok.Builder;
import lombok.Data;

/**
 * LLM 模型连通性测试结果。
 */
@Data
@Builder
public class LlmModelTestResponse {
    private boolean available;
    private String provider;
    private String model;
    /** 模型简短回复（成功时） */
    private String reply;
    /** 耗时毫秒 */
    private Long latencyMs;
    /** 失败原因 */
    private String errorMessage;
}
