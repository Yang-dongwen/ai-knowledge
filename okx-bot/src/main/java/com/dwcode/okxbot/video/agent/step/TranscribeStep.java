package com.dwcode.okxbot.video.agent.step;

import com.dwcode.okxbot.video.dto.TranscriptionResult;
import com.dwcode.okxbot.video.entity.VideoTaskEntity;
import com.dwcode.okxbot.video.enums.UnderstandingMode;
import com.dwcode.okxbot.video.enums.VideoTaskStatus;
import com.dwcode.okxbot.video.service.StorageService;
import com.dwcode.okxbot.video.service.TranscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class TranscribeStep implements VideoPipelineStep {

    private final TranscriptionService transcriptionService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "transcribe";
    }

    @Override
    public VideoTaskStatus runningStatus() {
        return VideoTaskStatus.TRANSCRIBING;
    }

    @Override
    public String stepLabel(VideoPipelineContext ctx) {
        return "正在转录音频";
    }

    @Override
    public boolean applies(UnderstandingMode mode) {
        return mode != null && mode.needsWhisper();
    }

    @Override
    public void execute(VideoPipelineContext ctx) throws Exception {
        VideoTaskEntity task = ctx.getTask();
        String audioPath = ctx.getDownload() != null
                ? ctx.getDownload().getAudioPath()
                : task.getAudioPath();
        long t0 = System.currentTimeMillis();
        TranscriptionResult transcription = transcriptionService.transcribe(
                audioPath, task.getLanguage());
        long transcribeMs = System.currentTimeMillis() - t0;
        task.setTranscribeDurationMs(transcribeMs);
        if (transcription.getDurationSeconds() != null) {
            task.setDurationSeconds(transcription.getDurationSeconds());
        }
        task.setTranscriptionJson(objectMapper.writeValueAsString(transcription));
        task.setTranscriptionPath(storageService.saveJson(
                storageService.resolveTranscriptionPath(ctx.getTaskIdStr()), transcription));
        task.setUpdatedAt(LocalDateTime.now());
        ctx.setTranscription(transcription);
        log.info("步骤耗时: taskId={}, transcribe={}ms", ctx.getTaskId(), transcribeMs);
    }
}
