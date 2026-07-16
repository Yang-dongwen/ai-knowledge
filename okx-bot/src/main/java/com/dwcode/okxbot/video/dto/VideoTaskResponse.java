package com.dwcode.okxbot.video.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 任务提交 / 状态查询响应。
 */
@Data
@Builder
public class VideoTaskResponse {
    private String taskId;
    private String status;
    private String url;
    private String title;
    /** 平台：douyin / bilibili / youtube / other */
    private String platform;
    /** 本任务 LLM 供应商 */
    private String llmProvider;
    /** 本任务 LLM 模型 */
    private String llmModel;
    /** 理解模式 */
    private String understandingMode;
    private String omniProvider;
    private String omniModel;
    private String currentStep;
    private String errorMessage;
    private Double durationSeconds;
    /** 本地视频是否可下载/播放 */
    private Boolean videoAvailable;
    private String videoPath;
    private String audioPath;
    private String createdAt;
    private String finishedAt;
    /** 开始处理时间 */
    private String startedAt;
    /** 下载步骤耗时 ms */
    private Long downloadDurationMs;
    /** 转录步骤耗时 ms */
    private Long transcribeDurationMs;
    /** 画面理解耗时 ms */
    private Long understandDurationMs;
    /** 总结步骤耗时 ms */
    private Long summarizeDurationMs;
    /** 全流程总耗时 ms */
    private Long totalDurationMs;
    private Boolean degraded;
    private String degradeReason;
    /** 成功时包含完整结果（详情查询） */
    private VideoSummaryResponse result;
}
