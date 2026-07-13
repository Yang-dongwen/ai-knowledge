package com.dwcode.okxbot.aigen.adapter.tts;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.util.ProcessExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 探测音频时长（优先 ffprobe / ffmpeg）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AudioDurationHelper {

    private final AigenProperties aigenProperties;
    private final VideoProperties videoProperties;
    private final ProcessExecutor processExecutor;

    public long probeDurationMs(Path audioFile, long fallbackMs) {
        if (audioFile == null || !Files.isRegularFile(audioFile)) {
            return fallbackMs;
        }
        String ffprobe = resolveFfprobe();
        if (ffprobe != null) {
            try {
                String out = processExecutor.execute(List.of(
                        ffprobe,
                        "-v", "error",
                        "-show_entries", "format=duration",
                        "-of", "default=noprint_wrappers=1:nokey=1",
                        audioFile.toAbsolutePath().toString()
                ), 30);
                String line = out.trim().split("\\R")[0].trim();
                double sec = Double.parseDouble(line);
                if (sec > 0.05) {
                    return Math.max(300L, Math.round(sec * 1000));
                }
            } catch (Exception e) {
                log.debug("ffprobe 测时长失败: {}", e.getMessage());
            }
        }
        // 粗估：mp3 ~16KB/s 低码率；wav 按大小
        try {
            long bytes = Files.size(audioFile);
            String name = audioFile.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".wav")) {
                // 16bit mono 16kHz ≈ 32KB/s
                long ms = bytes * 1000L / 32000L;
                if (ms > 200) {
                    return ms;
                }
            } else {
                long ms = bytes * 1000L / 16000L;
                if (ms > 200) {
                    return ms;
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return fallbackMs;
    }

    private String resolveFfprobe() {
        String configured = aigenProperties.getTts().getFfprobePath();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String ffmpeg = aigenProperties.getTts().getFfmpegPath();
        if (ffmpeg == null || ffmpeg.isBlank()) {
            ffmpeg = videoProperties.getFfmpegPath();
        }
        if (ffmpeg == null || ffmpeg.isBlank()) {
            return "ffprobe";
        }
        String p = ffmpeg.trim().replace('\\', '/');
        if (p.toLowerCase(Locale.ROOT).endsWith("ffmpeg.exe")) {
            return ffmpeg.substring(0, ffmpeg.length() - "ffmpeg.exe".length()) + "ffprobe.exe";
        }
        if (p.toLowerCase(Locale.ROOT).endsWith("ffmpeg")) {
            return ffmpeg.substring(0, ffmpeg.length() - "ffmpeg".length()) + "ffprobe";
        }
        return "ffprobe";
    }
}
