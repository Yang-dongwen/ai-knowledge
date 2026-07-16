package com.dwcode.okxbot.common.ai;

import lombok.Builder;
import lombok.Value;

/**
 * 单次 LLM 调用参数（任务级 / 测试级可覆盖）。
 */
@Value
@Builder
public class LlmCallOptions {

    /** 温度，null 表示用 video.llm 默认 */
    Double temperature;

    /** max_tokens，null 表示用 video.llm 默认 */
    Integer maxTokens;

    /**
     * 瞬时错误最大重试次数（不含首次）。
     * null 表示用 video.llm 默认；测试场景应传 0。
     */
    Integer maxRetries;

    /**
     * 读超时秒数。null 表示默认 180s；模型测试应传较短值。
     */
    Integer timeoutSeconds;

    /**
     * OpenAI 兼容 response_format，如 {@code json_object}。
     * null 表示不设置（纯文本）。
     * Phase B：分镜 / 摘要等结构化场景建议开启。
     */
    String responseFormat;

    /** 便捷：JSON Object 模式 */
    public static LlmCallOptions.LlmCallOptionsBuilder jsonObject() {
        return LlmCallOptions.builder().responseFormat("json_object");
    }
}
