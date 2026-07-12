package com.dwcode.okxbot.video.dto;

import lombok.Data;

/**
 * 失败/暂停任务重试请求（可重新指定 LLM）。
 */
@Data
public class VideoRetryRequest {

    /** 可选，覆盖原任务 LLM 供应商 */
    private String llmProvider;

    /** 可选，覆盖原任务 LLM 模型 */
    private String llmModel;
}
