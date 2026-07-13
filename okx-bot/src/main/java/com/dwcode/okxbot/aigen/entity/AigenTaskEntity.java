package com.dwcode.okxbot.aigen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 视频生成任务。
 */
@Data
@TableName("aigen_task")
public class AigenTaskEntity {

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private String title;
    private String prompt;
    private String negativePrompt;
    private String templateId;
    /** PENDING / PLANNING / ASSET_GENERATING / RENDERING / SUCCESS / FAILED / CANCELLED */
    private String status;
    private String currentStep;
    /** 0–100 */
    private Integer progress;

    private String language;
    private String aspectRatio;
    private Integer targetDurationSec;
    private String styleJson;
    private String voiceId;
    private String bgmId;

    private String llmProvider;
    private String llmModel;

    private String storyboardJson;
    private String storyboardPath;
    private String workDir;
    private String outputPath;
    private String posterPath;
    private Long outputSizeBytes;
    private Double durationSeconds;

    private String errorMessage;
    private Long planDurationMs;
    private Long assetDurationMs;
    private Long renderDurationMs;
    private Long totalDurationMs;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
