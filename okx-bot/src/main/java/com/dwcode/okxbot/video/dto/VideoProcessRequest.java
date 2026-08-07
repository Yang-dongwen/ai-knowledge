package com.dwcode.okxbot.video.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交视频处理任务请求。
 */
@Data
public class VideoProcessRequest {

    /** 视频链接（抖音/B站/YouTube 等）；对齐 video_task.source_url VARCHAR(1024) */
    @NotBlank(message = "url 不能为空")
    @Size(max = 1024, message = "url 最长 1024 字符")
    private String url;

    @Valid
    private VideoProcessOptions options;
}
