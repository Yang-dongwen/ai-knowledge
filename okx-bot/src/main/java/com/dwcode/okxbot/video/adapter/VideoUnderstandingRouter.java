package com.dwcode.okxbot.video.adapter;

import com.dwcode.okxbot.video.adapter.NvidiaOmniVideoAdapter.MediaOmniException;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.port.VideoUnderstandingCommand;
import com.dwcode.okxbot.video.port.VideoUnderstandingProtocol;
import com.dwcode.okxbot.video.port.VisualUnderstandingResult;
import com.dwcode.okxbot.video.port.VisualUnderstandingResult.ChunkUnderstanding;
import com.dwcode.okxbot.video.service.MediaChunkPrepareService;
import com.dwcode.okxbot.video.service.MediaChunkPrepareService.TimeWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 按 protocol 选择 Omni / 抽帧，Omni 媒体失败时可回退 FrameSample。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoUnderstandingRouter {

    private final NvidiaOmniVideoAdapter omniAdapter;
    private final FrameSampleVlmAdapter frameSampleVlmAdapter;
    private final MockVideoUnderstandingAdapter mockAdapter;
    private final MediaChunkPrepareService mediaChunkPrepareService;
    private final VideoProperties videoProperties;

    public record WindowResult(ChunkUnderstanding chunk, String protocolUsed) {
    }

    public String resolveProtocol(VideoUnderstandingCommand cmd) {
        String fromCmd = cmd != null ? cmd.getProtocol() : null;
        String fromCfg = videoProperties.getUnderstanding() != null
                ? videoProperties.getUnderstanding().getProtocol()
                : null;
        return VideoUnderstandingProtocol.normalize(fromCmd != null ? fromCmd : fromCfg);
    }

    public boolean isMock(String protocol) {
        boolean cfgMock = videoProperties.getUnderstanding() != null
                && videoProperties.getUnderstanding().isMock();
        return VideoUnderstandingProtocol.isMock(protocol, cfgMock);
    }

    public VisualUnderstandingResult understandMock(VideoUnderstandingCommand cmd) throws Exception {
        return mockAdapter.understand(cmd);
    }

    public WindowResult understandWindow(String protocol,
                                         VideoUnderstandingCommand cmd,
                                         Path video,
                                         TimeWindow window,
                                         int index,
                                         String asrSlice) throws Exception {
        String p = VideoUnderstandingProtocol.normalize(protocol);
        if (VideoUnderstandingProtocol.forceFrame(p) || !VideoUnderstandingProtocol.useOmni(p)) {
            return runFrame(cmd, video, window, index, asrSlice, VideoUnderstandingProtocol.FRAME);
        }
        try {
            Path piece = mediaChunkPrepareService.extractChunk(
                    cmd.getTaskId(), video, window, index, cmd.isStripAudio());
            ChunkUnderstanding chunk = omniAdapter.understandChunk(
                    piece, window.startSec(), window.endSec(), cmd.getLanguage(),
                    cmd.getProviderKey(), cmd.getModelId(),
                    cmd.isUseAudioInVideo(), asrSlice);
            return new WindowResult(chunk, VideoUnderstandingProtocol.OMNI);
        } catch (MediaOmniException mediaEx) {
            log.warn("Omni 媒体失败，尝试 FrameSample: {}", mediaEx.getMessage());
            if (videoProperties.getUnderstanding() == null
                    || !videoProperties.getUnderstanding().isFallbackFrameVlm()) {
                throw mediaEx;
            }
            return runFrame(cmd, video, window, index, asrSlice,
                    VideoUnderstandingProtocol.FRAME_FALLBACK);
        }
    }

    private WindowResult runFrame(VideoUnderstandingCommand cmd,
                                  Path video,
                                  TimeWindow window,
                                  int index,
                                  String asrSlice,
                                  String protocolUsed) throws Exception {
        List<Path> frames = mediaChunkPrepareService.extractFrames(
                cmd.getTaskId(), video, window, index);
        ChunkUnderstanding chunk = frameSampleVlmAdapter.understandFrames(
                frames, window.startSec(), window.endSec(), cmd.getLanguage(),
                cmd.getProviderKey(), cmd.getModelId(), asrSlice);
        return new WindowResult(chunk, protocolUsed);
    }
}
