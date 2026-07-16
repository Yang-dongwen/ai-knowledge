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
    /**
     * 流水线模式：template（口播模板）| visual（画面优先 Timeline）
     */
    private String pipelineMode;
    /** none | bgm_only | tts */
    private String audioMode;
    /** 风格预设，如 cinematic-dark */
    private String stylePreset;
    private Integer shotCount;
    private Integer assetDoneCount;
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

    /** visual 出图：供应商 key（如 nvidia） */
    private String imageProvider;
    /** visual 出图：模型 ID（ai_model_config capability=image） */
    private String imageModel;

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
