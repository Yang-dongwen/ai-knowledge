package com.dwcode.okxbot.aigen.dto;

import lombok.Data;

/**
 * 创建生成任务的可选项。
 */
@Data
public class AigenCreateOptions {
    private String language;
    /** 9:16 / 16:9 / 1:1 */
    private String aspectRatio;
    private Integer targetDurationSec;
    private String voiceId;
    private String bgmId;
    private String llmProvider;
    private String llmModel;
    private String negativePrompt;
    /** 可选 JSON 字符串或由前端传对象时需另接 Map；Phase 0 用字符串 */
    private String styleJson;
}
