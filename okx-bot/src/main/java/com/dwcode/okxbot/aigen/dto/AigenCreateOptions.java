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

    /**
     * template | visual；空则用 aigen.default-pipeline-mode
     */
    private String pipelineMode;
    /** none | bgm_only | tts（visual 模式） */
    private String audioMode;
    /** 风格预设 cinematic-dark / clean-tech / … */
    private String stylePreset;

    /**
     * visual 出图模型 ID（ai_model_config.model_id，capability=image）。
     * 空则取库中第一个启用生图模型。
     */
    private String imageModel;
    /** visual 出图供应商 key（可选，与 imageModel 组合定位） */
    private String imageProvider;
}
