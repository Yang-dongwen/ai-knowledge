package com.dwcode.okxbot.video.port;

/**
 * 视频多模态理解端口。
 * <p>
 * Mock 实现整段 {@link #understand}；Omni / 抽帧按分片调用各自的 chunk API，
 * 由 {@code VideoUnderstandingRouter} 按 protocol 选择并回退。
 */
public interface VideoUnderstandingPort {

    /**
     * mock | nvidia-omni-chat | frame-vlm
     */
    String protocolId();

    /**
     * 整段理解。默认不支持（分片适配器）；Mock 覆盖本方法。
     */
    default VisualUnderstandingResult understand(VideoUnderstandingCommand cmd) throws Exception {
        throw new UnsupportedOperationException(
                protocolId() + " 按分片调用，不支持整段 understand");
    }
}
