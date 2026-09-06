package com.dwcode.okxbot.video.agent.step;

import com.dwcode.okxbot.video.entity.VideoTaskEntity;
import com.dwcode.okxbot.video.enums.UnderstandingMode;
import com.dwcode.okxbot.video.enums.VideoTaskStatus;
import com.dwcode.okxbot.video.service.VideoDownloadService;
import com.dwcode.okxbot.video.service.VideoDownloadService.DownloadResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class DownloadStep implements VideoPipelineStep {

    private final VideoDownloadService downloadService;

    @Override
    public String name() {
        return "download";
    }

    @Override
    public VideoTaskStatus runningStatus() {
        return VideoTaskStatus.DOWNLOADING;
    }

    @Override
    public String stepLabel(VideoPipelineContext ctx) {
        UnderstandingMode mode = ctx.getMode();
        boolean downloadOnly = mode != null && mode.isDownloadOnly();
        return downloadOnly ? "正在下载视频" : "正在下载视频并提取音频";
    }

    @Override
    public boolean applies(UnderstandingMode mode) {
        return true;
    }

    @Override
    public void execute(VideoPipelineContext ctx) throws Exception {
        VideoTaskEntity task = ctx.getTask();
        UnderstandingMode mode = ctx.getMode();
        boolean extractAudio = mode == null || !mode.isDownloadOnly();
        long t0 = System.currentTimeMillis();
        DownloadResult download = downloadService.download(
                task.getSourceUrl(), ctx.getTaskIdStr(), extractAudio);
        long downloadMs = System.currentTimeMillis() - t0;
        task.setDownloadDurationMs(downloadMs);
        task.setTitle(download.getTitle());
        task.setDurationSeconds(download.getDurationSeconds());
        task.setVideoPath(download.getVideoPath());
        task.setAudioPath(download.getAudioPath());
        task.setUpdatedAt(LocalDateTime.now());
        ctx.setDownload(download);
        log.info("步骤耗时: taskId={}, download={}ms", ctx.getTaskId(), downloadMs);
    }
}
