package com.dwcode.okxbot.video.service;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.util.ProcessExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 将长视频切分为可送入 Omni 的短片（ffmpeg），并控制体积。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaChunkPrepareService {

    private final VideoProperties videoProperties;
    private final ProcessExecutor processExecutor;

    public record TimeWindow(double startSec, double endSec) {
        public double duration() {
            return Math.max(0, endSec - startSec);
        }
    }

    /**
     * 按设计分档：short single / medium uniform / long sparse。
     */
    public List<TimeWindow> planWindows(double durationSec) {
        VideoProperties.Understanding u = videoProperties.getUnderstanding();
        List<TimeWindow> windows = new ArrayList<>();
        if (durationSec <= 0) {
            windows.add(new TimeWindow(0, Math.max(1, durationSec)));
            return windows;
        }
        int maxReq = Math.max(10, u.getMaxRequestVideoSeconds());
        int shortMax = Math.max(1, u.getShortMaxSeconds());
        int mediumMax = Math.max(shortMax + 1, u.getMediumMaxSeconds());
        int maxChunks = Math.max(1, u.getMaxChunksPerTask());

        if (durationSec <= shortMax) {
            windows.add(new TimeWindow(0, durationSec));
            return windows;
        }

        if (durationSec <= mediumMax) {
            double chunkLen = Math.min(u.getChunkSeconds(), maxReq);
            for (double start = 0; start < durationSec && windows.size() < maxChunks; start += chunkLen) {
                double end = Math.min(start + chunkLen, durationSec);
                if (end - start < 2 && !windows.isEmpty()) {
                    TimeWindow last = windows.remove(windows.size() - 1);
                    windows.add(new TimeWindow(last.startSec(), end));
                } else {
                    windows.add(new TimeWindow(start, end));
                }
            }
            return windows;
        }

        // long sparse
        double stride = Math.max(5, u.getSampleStrideSeconds());
        double window = Math.min(u.getSampleWindowSeconds(), maxReq);
        for (double start = 0; start < durationSec && windows.size() < maxChunks; start += stride) {
            double end = Math.min(start + window, durationSec);
            if (end - start >= 1) {
                windows.add(new TimeWindow(start, end));
            }
        }
        return windows;
    }

    public boolean isPartialCoverage(double durationSec) {
        return durationSec > videoProperties.getUnderstanding().getMediumMaxSeconds();
    }

    /**
     * 导出单片 mp4，控制体积；失败抛业务异常。
     */
    public Path extractChunk(String taskId, Path sourceVideo, TimeWindow window, int index, boolean stripAudio)
            throws Exception {
        VideoProperties.Understanding u = videoProperties.getUnderstanding();
        Path taskDir = Path.of(videoProperties.getWorkDir(), taskId).toAbsolutePath().normalize();
        Path chunkDir = taskDir.resolve("chunks");
        Files.createDirectories(chunkDir);
        Path out = chunkDir.resolve(String.format("chunk_%03d.mp4", index));

        long maxBytes = (long) (u.getMaxUploadBytes() * u.getPayloadHeadroom());
        int height = u.getTargetHeight();
        int fps = Math.max(1, u.getTargetFps());
        int crf = 28;
        Exception last = null;

        for (int attempt = 1; attempt <= Math.max(1, u.getReencodeMaxAttempts()); attempt++) {
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add(videoProperties.getFfmpegPath());
                cmd.add("-y");
                cmd.add("-ss");
                cmd.add(String.format(java.util.Locale.ROOT, "%.3f", window.startSec()));
                cmd.add("-i");
                cmd.add(sourceVideo.toAbsolutePath().toString());
                cmd.add("-t");
                cmd.add(String.format(java.util.Locale.ROOT, "%.3f", window.duration()));
                cmd.add("-vf");
                cmd.add("scale=-2:" + height + ",fps=" + fps);
                cmd.add("-c:v");
                cmd.add("libx264");
                cmd.add("-preset");
                cmd.add("veryfast");
                cmd.add("-crf");
                cmd.add(String.valueOf(crf));
                if (stripAudio) {
                    cmd.add("-an");
                } else {
                    cmd.add("-c:a");
                    cmd.add("aac");
                    cmd.add("-b:a");
                    cmd.add("64k");
                }
                cmd.add("-movflags");
                cmd.add("+faststart");
                cmd.add(out.toAbsolutePath().toString());

                processExecutor.execute(cmd, 300);
                if (!Files.isRegularFile(out) || Files.size(out) == 0) {
                    throw new BusinessException("ffmpeg 切片产物为空: " + out);
                }
                long size = Files.size(out);
                if (size <= maxBytes) {
                    log.info("切片完成: idx={}, range={}~{}, size={}KB, attempt={}",
                            index, window.startSec(), window.endSec(), size / 1024, attempt);
                    return out;
                }
                log.warn("切片超体积: idx={}, size={} > {}, 加重压码", index, size, maxBytes);
                crf = Math.min(40, crf + 4);
                if (height > 360) {
                    height = 360;
                }
                if (fps > 1) {
                    fps = 1;
                }
            } catch (Exception e) {
                last = e;
                log.warn("切片失败 attempt={}: {}", attempt, e.getMessage());
            }
        }
        throw new BusinessException("[OMNI] payload too large or extract failed: "
                + (last != null ? last.getMessage() : "unknown"));
    }

    /**
     * 抽帧 JPEG 列表（FrameSample）。
     */
    public List<Path> extractFrames(String taskId, Path sourceVideo, TimeWindow window, int chunkIndex)
            throws Exception {
        VideoProperties.Understanding u = videoProperties.getUnderstanding();
        Path frameDir = Path.of(videoProperties.getWorkDir(), taskId, "frames",
                String.format("c%03d", chunkIndex)).toAbsolutePath().normalize();
        Files.createDirectories(frameDir);

        double interval = Math.max(0.5, u.getFrameIntervalSeconds());
        int maxFrames = Math.max(1, u.getFrameMaxPerChunk());
        int edge = Math.max(256, u.getFrameMaxEdge());
        // ffmpeg quality 2-31, lower better; map jpeg 85 ~ q:v 5
        int qv = Math.max(2, Math.min(31, (100 - u.getFrameJpegQuality()) / 5));

        List<Path> frames = new ArrayList<>();
        double t = window.startSec();
        int i = 0;
        while (t < window.endSec() && i < maxFrames) {
            Path out = frameDir.resolve(String.format("f_%05d.jpg", i));
            List<String> cmd = List.of(
                    videoProperties.getFfmpegPath(),
                    "-y",
                    "-ss", String.format(java.util.Locale.ROOT, "%.3f", t),
                    "-i", sourceVideo.toAbsolutePath().toString(),
                    "-frames:v", "1",
                    "-vf", "scale='min(" + edge + ",iw)':-2",
                    "-q:v", String.valueOf(qv),
                    out.toAbsolutePath().toString()
            );
            try {
                processExecutor.execute(cmd, 60);
                if (Files.isRegularFile(out) && Files.size(out) > 0) {
                    frames.add(out);
                }
            } catch (Exception e) {
                log.debug("抽帧失败 t={}: {}", t, e.getMessage());
            }
            t += interval;
            i++;
        }
        if (frames.isEmpty()) {
            throw new BusinessException("[FRAME] 未抽到有效帧");
        }
        return frames;
    }

    public void cleanupChunks(String taskId) {
        if (!videoProperties.getUnderstanding().isCleanupChunks()) {
            return;
        }
        Path chunks = Path.of(videoProperties.getWorkDir(), taskId, "chunks").toAbsolutePath().normalize();
        deleteTree(chunks);
        Path frames = Path.of(videoProperties.getWorkDir(), taskId, "frames").toAbsolutePath().normalize();
        deleteTree(frames);
    }

    private void deleteTree(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best-effort
                }
            });
        } catch (Exception e) {
            log.warn("清理目录失败 {}: {}", dir, e.getMessage());
        }
    }
}
