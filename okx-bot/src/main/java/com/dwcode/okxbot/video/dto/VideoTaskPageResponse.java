package com.dwcode.okxbot.video.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 任务列表分页响应。
 */
@Data
@Builder
public class VideoTaskPageResponse {
    private List<VideoTaskResponse> items;
    private long total;
    private int page;
    private int size;
}
