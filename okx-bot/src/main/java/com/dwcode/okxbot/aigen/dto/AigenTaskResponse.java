package com.dwcode.okxbot.aigen.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 生成任务响应。
 */
@Data
@Builder
public class AigenTaskResponse {
    private String id;
    private String title;
    private String prompt;
    private String templateId;
    private String pipelineMode;
    private String audioMode;
    private String stylePreset;
    private Integer shotCount;
    private Integer assetDoneCount;
    private String status;
    private String currentStep;
    private Integer progress;
    private String language;
    private String aspectRatio;
    private Integer targetDurationSec;
    private String voiceId;
    private String bgmId;
    private String llmProvider;
    private String llmModel;
    private String imageProvider;
    private String imageModel;
    private Boolean enhanceImagePrompt;
    private String errorMessage;
    private Double durationSeconds;
    private Boolean outputAvailable;
    private Long planDurationMs;
    private Long assetDurationMs;
    private Long renderDurationMs;
    private Long totalDurationMs;
    private String startedAt;
    private String finishedAt;
    private String createdAt;
    private String updatedAt;
}
