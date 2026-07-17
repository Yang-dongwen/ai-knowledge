package com.dwcode.okxbot.chat.agent.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 三工具任务统一摘要（Agent 层 mapping，不污染原 DTO）。
 */
@Data
@Builder
public class AgentTaskSummary {
    /** video | imggen | aigen */
    private String type;
    private String taskId;
    private String status;
    private String title;
    private String prompt;
    private String errorMessage;
    private String resultHint;
    private String openPath;
    private String createdAt;
    private String updatedAt;
    private Integer progress;
}
