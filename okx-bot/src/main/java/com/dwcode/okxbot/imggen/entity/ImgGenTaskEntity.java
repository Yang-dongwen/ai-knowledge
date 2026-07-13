package com.dwcode.okxbot.imggen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 文生图任务。
 */
@Data
@TableName("imggen_task")
public class ImgGenTaskEntity {

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private String title;
    private String prompt;
    private String enhancedPrompt;
    private String negativePrompt;
    private String status;
    private String currentStep;
    private Integer progress;

    private String provider;
    private String model;
    private String aspectRatio;
    private Integer width;
    private Integer height;
    private Integer steps;
    private Integer n;
    private Long seed;
    /** 0/1 */
    private Integer enhanceEnabled;

    private String llmProvider;
    private String llmModel;

    private String resultJson;
    private String workDir;
    private String coverPath;
    private String errorMessage;

    private String providerRequestId;
    private Long enhanceDurationMs;
    private Long generateDurationMs;
    private Long totalDurationMs;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
