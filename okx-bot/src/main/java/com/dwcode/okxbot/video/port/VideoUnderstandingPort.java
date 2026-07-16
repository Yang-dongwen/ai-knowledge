package com.dwcode.okxbot.video.port;

/**
 * 视频多模态理解端口（对齐 imggen ImageGenPort）。
 */
public interface VideoUnderstandingPort {

    VisualUnderstandingResult understand(VideoUnderstandingCommand cmd) throws Exception;
}
