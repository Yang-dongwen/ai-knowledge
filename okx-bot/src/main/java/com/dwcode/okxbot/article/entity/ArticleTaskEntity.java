package com.dwcode.okxbot.article.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文章/新闻提取任务。
 */
@Data
@TableName("article_task")
public class ArticleTaskEntity {

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
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

    /** url | paste | url_and_paste */
    private String inputMode;
    private String pasteText;
    /** 正文截断副本；全文见 mainTextPath */
    private String mainText;
    private Integer mainTextChars;
    /** 0/1 */
    private Integer forcePasteOnly;
    /** 0/1 */
    private Integer allowPasteFallback;
    /**
     * 单轮标志：/paste 入队置 1；pipeline 消费后立即清 0；retry 强制 0。
     */
    private Integer pasteResume;

    private String llmProvider;
    private String llmModel;
    /** 0/1 */
    private Integer extractMindMap;
    /** 0/1 */
    private Integer generateRewrite;
    /** JSON 数组字符串 */
    private String rewriteVariants;
    private String requestOptionsJson;

    private String coreJson;
    private String rewriteJson;
    private String resultJson;
    private String rawHtmlPath;
    private String mainTextPath;
    private String errorCode;
    private String errorMessage;
    /** 0/1 */
    private Integer degraded;
    private String degradeReason;
    private Double qualityScore;

    private Long resolveDurationMs;
    private Long fetchDurationMs;
    private Long extractDurationMs;
    private Long coreDurationMs;
    private Long rewriteDurationMs;
    private Long totalDurationMs;

    private String workDir;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
