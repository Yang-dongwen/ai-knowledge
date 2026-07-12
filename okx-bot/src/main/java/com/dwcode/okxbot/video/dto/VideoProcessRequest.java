package com.dwcode.okxbot.video.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 提交视频处理任务请求。
 */
@Data
public class VideoProcessRequest {

    /** 视频链接（抖音/B站/YouTube 等） */
    @NotBlank(message = "url 不能为空")
    private String url;

    private VideoProcessOptions options;
}
