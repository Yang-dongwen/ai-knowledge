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
    private String currentStep;
    private String errorMessage;
    private Double durationSeconds;
    /** 本地视频是否可下载/播放 */
    private Boolean videoAvailable;
    private String videoPath;
    private String audioPath;
    private String createdAt;
    private String finishedAt;
    /** 成功时包含完整结果（详情查询） */
    private VideoSummaryResponse result;
}
