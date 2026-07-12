package com.dwcode.okxbot.video.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 视频处理任务实体。
 */
@Data
@TableName("video_task")
public class VideoTaskEntity {

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 源视频 URL */
    private String sourceUrl;

    /** 视频标题 */
    private String title;

    /** 平台：douyin / bilibili / youtube / xiaohongshu / other */
    private String platform;

    /** 任务状态 PENDING/DOWNLOADING/TRANSCRIBING/SUMMARIZING/SUCCESS/FAILED */
    private String status;

    /** 当前步骤说明 */
    private String currentStep;

    /** 语言 */
    private String language;

    /** 本任务使用的 LLM 供应商（ai.providers key） */
    private String llmProvider;

    /** 本任务使用的 LLM 模型 ID */
    private String llmModel;

    /** 是否提取思维导图 1/0 */
    private Integer extractMindMap;

    /** 是否生成 repurpose 脚本 1/0 */
    private Integer generateRepurposeScript;

    /** 视频时长（秒） */
    private Double durationSeconds;

    /** 本地视频路径 */
    private String videoPath;

    /** 本地音频路径 */
    private String audioPath;

    /** 转录 JSON 文件路径（文件系统副本） */
    private String transcriptionPath;

    /** 摘要 JSON 文件路径（文件系统副本） */
    private String summaryPath;

    /** 转录 JSON（带时间戳完整文字，便于前端展示与搜索） */
    private String transcriptionJson;

    /** AI 核心内容 JSON（keyPoints/chapters/mindMap/repurpose） */
    private String summaryJson;

    /** 完整结构化结果 JSON（含元数据 + summary + transcription） */
    private String resultJson;

    /** 错误信息 */
    private String errorMessage;

    /** 下载步骤耗时（毫秒） */
    private Long downloadDurationMs;

    /** 转录步骤耗时（毫秒） */
    private Long transcribeDurationMs;

    /** 总结步骤耗时（毫秒） */
    private Long summarizeDurationMs;

    /** 全流程总耗时（毫秒，从 startedAt 到 finishedAt） */
    private Long totalDurationMs;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
