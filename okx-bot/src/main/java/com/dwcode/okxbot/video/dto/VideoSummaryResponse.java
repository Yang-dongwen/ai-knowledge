package com.dwcode.okxbot.video.dto;

import lombok.Data;

/**
 * 视频处理最终结构化输出。
 */
@Data
public class VideoSummaryResponse {
    private String videoId;
    private String title;
    private Double duration;
    private String sourceUrl;
    private VideoSummaryPart summary;
    private TranscriptionResult transcription;
}
