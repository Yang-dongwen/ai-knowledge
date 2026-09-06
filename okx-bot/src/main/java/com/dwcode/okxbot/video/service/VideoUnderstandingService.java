package com.dwcode.okxbot.video.service;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.adapter.VideoUnderstandingRouter;
import com.dwcode.okxbot.video.adapter.VideoUnderstandingRouter.WindowResult;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.dto.TranscriptionResult;
import com.dwcode.okxbot.video.dto.TranscriptionSegment;
import com.dwcode.okxbot.video.exception.UnderstandingDegradedException;
import com.dwcode.okxbot.video.port.VideoUnderstandingCommand;
import com.dwcode.okxbot.video.port.VideoUnderstandingProtocol;
import com.dwcode.okxbot.video.port.VisualUnderstandingResult;
import com.dwcode.okxbot.video.port.VisualUnderstandingResult.ChunkUnderstanding;
import com.dwcode.okxbot.video.service.MediaChunkPrepareService.TimeWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * 多模态理解编排：分片 → Router（Omni / FrameSample）→ 聚合。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoUnderstandingService {

    private final VideoProperties videoProperties;
    private final MediaChunkPrepareService mediaChunkPrepareService;
    private final VideoUnderstandingRouter understandingRouter;

    public VisualUnderstandingResult understand(VideoUnderstandingCommand cmd,
                                                TranscriptionResult asr,
                                                BooleanSupplier pauseCheck) throws Exception {
        long t0 = System.currentTimeMillis();
        String protocol = understandingRouter.resolveProtocol(cmd);

        if (understandingRouter.isMock(protocol)) {
            VisualUnderstandingResult mock = understandingRouter.understandMock(cmd);
            mock.setElapsedMs(System.currentTimeMillis() - t0);
            return mock;
        }

        Path video = Path.of(cmd.getVideoPath());
        if (!video.toFile().isFile()) {
            throw new BusinessException("[OMNI] 视频文件不存在: " + cmd.getVideoPath());
        }
        double duration = cmd.getDurationSeconds() != null ? cmd.getDurationSeconds() : 0;
        List<TimeWindow> windows = mediaChunkPrepareService.planWindows(duration);
        boolean partial = mediaChunkPrepareService.isPartialCoverage(duration);

        VisualUnderstandingResult result = new VisualUnderstandingResult();
        result.setModelId(cmd.getModelId());
        result.setProtocol(VideoUnderstandingProtocol.forceFrame(protocol)
                || !VideoUnderstandingProtocol.useOmni(protocol)
                ? VideoUnderstandingProtocol.FRAME
                : VideoUnderstandingProtocol.OMNI);
        result.setPartial(partial);

        int okChunks = 0;
        Exception lastErr = null;
        VideoProperties.Understanding cfg = videoProperties.getUnderstanding();

        for (int i = 0; i < windows.size(); i++) {
            if (pauseCheck != null && pauseCheck.getAsBoolean()) {
                throw new BusinessException("用户已暂停");
            }
            TimeWindow w = windows.get(i);
            String asrSlice = sliceAsr(asr, w.startSec() - 5, w.endSec() + 5);
            try {
                WindowResult wr = understandingRouter.understandWindow(
                        protocol, cmd, video, w, i, asrSlice);
                ChunkUnderstanding chunk = wr.chunk();
                if (wr.protocolUsed() != null) {
                    result.setProtocol(wr.protocolUsed());
                }
                if (chunk != null) {
                    chunk.setIndex(i);
                    result.getChunks().add(chunk);
                    mergeChunk(result, chunk);
                    okChunks++;
                }
            } catch (Exception e) {
                lastErr = e;
                log.error("分片理解失败 idx={}: {}", i, e.getMessage());
            }
        }

        if (okChunks == 0) {
            String reason = lastErr != null ? lastErr.getMessage() : "全部视觉分片失败";
            if (cfg != null && cfg.isFallbackToAudio() && asr != null
                    && asr.getText() != null && !asr.getText().isBlank()) {
                throw new UnderstandingDegradedException(reason, lastErr);
            }
            throw new BusinessException("[OMNI] " + reason);
        }

        if (result.getOverallVisualSummary() == null || result.getOverallVisualSummary().isBlank()) {
            StringBuilder sb = new StringBuilder();
            for (ChunkUnderstanding c : result.getChunks()) {
                if (c.getOverallVisualSummary() != null && !c.getOverallVisualSummary().isBlank()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(c.getOverallVisualSummary());
                }
            }
            result.setOverallVisualSummary(sb.toString());
        }
        result.setChunkCount(okChunks);
        result.setElapsedMs(System.currentTimeMillis() - t0);

        try {
            mediaChunkPrepareService.cleanupChunks(cmd.getTaskId());
        } catch (Exception e) {
            log.debug("cleanup chunks: {}", e.getMessage());
        }
        return result;
    }

    private void mergeChunk(VisualUnderstandingResult result, ChunkUnderstanding chunk) {
        result.getScenes().addAll(chunk.getScenes());
        result.getOnScreenTexts().addAll(chunk.getOnScreenTexts());
        result.getVisualKeyPoints().addAll(chunk.getVisualKeyPoints());
    }

    private String sliceAsr(TranscriptionResult asr, double start, double end) {
        if (asr == null) {
            return null;
        }
        if (asr.getSegments() != null && !asr.getSegments().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (TranscriptionSegment seg : asr.getSegments()) {
                if (seg.getEnd() < start || seg.getStart() > end) {
                    continue;
                }
                sb.append('[').append(String.format(Locale.ROOT, "%.1f", seg.getStart()))
                        .append("] ").append(seg.getText()).append('\n');
            }
            if (!sb.isEmpty()) {
                return sb.toString();
            }
        }
        return asr.getText();
    }
}
