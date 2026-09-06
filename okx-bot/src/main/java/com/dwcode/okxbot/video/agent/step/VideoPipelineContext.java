package com.dwcode.okxbot.video.agent.step;

import com.dwcode.okxbot.video.dto.TranscriptionResult;
import com.dwcode.okxbot.video.entity.VideoTaskEntity;
import com.dwcode.okxbot.video.enums.UnderstandingMode;
import com.dwcode.okxbot.video.port.VisualUnderstandingResult;
import com.dwcode.okxbot.video.service.VideoDownloadService.DownloadResult;
import lombok.Data;

import java.util.function.BooleanSupplier;

@Data
public class VideoPipelineContext {
    private Long taskId;
    private String taskIdStr;
    private VideoTaskEntity task;
    private long pipelineStartMs;
    private UnderstandingMode mode;
    private DownloadResult download;
    private TranscriptionResult transcription;
    private VisualUnderstandingResult visual;
    private boolean degraded;
    private String degradeReason;
    private BooleanSupplier pauseCheck;
}
