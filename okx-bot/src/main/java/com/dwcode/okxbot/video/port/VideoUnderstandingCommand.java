package com.dwcode.okxbot.video.port;

import lombok.Builder;
import lombok.Data;

/**
 * 多模态视频理解请求。
 */
@Data
@Builder
public class VideoUnderstandingCommand {
    private String taskId;
    private String videoPath;
    private String audioPath;
    private Double durationSeconds;
    private String language;
    private String providerKey;
    private String modelId;
    /** auto | nvidia-omni-chat | frame-vlm | mock */
    private String protocol;
    /** hybrid 时是否 strip 视觉片音轨 */
    private boolean stripAudio;
    /** omni_only 时 use_audio_in_video */
    private boolean useAudioInVideo;
    /** 完整 ASR 文本（可选，用于窗对齐） */
    private String priorTranscriptText;
    /** ASR 分段 JSON 可选；服务层也可直接传 segments */
    private Object priorSegments;
}
