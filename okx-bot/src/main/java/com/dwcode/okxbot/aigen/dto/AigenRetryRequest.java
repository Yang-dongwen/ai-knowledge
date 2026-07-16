package com.dwcode.okxbot.aigen.dto;

import lombok.Data;

/**
 * 重试任务请求：可重新指定 LLM（失败 / 取消 / 暂停 / 成功均可）。
 */
@Data
public class AigenRetryRequest {
    /** 可选，覆盖原任务 LLM 供应商 */
    private String llmProvider;
    /** 可选，覆盖原任务 LLM 模型 */
    private String llmModel;
    /** 可选，覆盖 visual 出图供应商 */
    private String imageProvider;
    /** 可选，覆盖 visual 出图模型 */
    private String imageModel;
}
