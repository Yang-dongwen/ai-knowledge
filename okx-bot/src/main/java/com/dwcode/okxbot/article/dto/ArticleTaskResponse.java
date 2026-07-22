package com.dwcode.okxbot.article.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 文章提取任务响应（list 轻量 / detail 可带 core/rewrite）。
 */
@Data
@Builder
public class ArticleTaskResponse {
    private String id;
    private String userId;
    private String sourceUrl;
    private String canonicalUrl;
    private String platform;
    private String supportLevel;
    private String title;
    private String author;
    private String status;
    private String currentStep;
    private Integer progress;
    private String language;
    private String inputMode;
    private String llmProvider;
    private String llmModel;
    private Boolean extractMindMap;
    private Boolean generateRewrite;
    private List<String> rewriteVariants;
    private String errorCode;
    private String errorMessage;
    private Boolean degraded;
    private String degradeReason;
    private Double qualityScore;
    private Integer mainTextChars;
    /** detail SUCCESS 时可选；list 不含 */
    private Object core;
    private Object rewrite;
    private String disclaimer;
    private Long resolveDurationMs;
    private Long fetchDurationMs;
    private Long extractDurationMs;
    private Long coreDurationMs;
    private Long rewriteDurationMs;
    private Long totalDurationMs;
    private String startedAt;
    private String finishedAt;
    private String createdAt;
    private String updatedAt;
}
