package com.dwcode.okxbot.video.service;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.util.ProcessExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 视频下载与音频提取服务。
 *
 * <p>外部依赖：yt-dlp、FFmpeg（经 {@link ProcessExecutor} 调用）。
 * <p>兼容 yt-dlp 未合并的情况（video-only + audio-only 分文件），常见于
 * JVM 环境 PATH 中无 ffmpeg，导致 yt-dlp 无法自动 merge。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoDownloadService {

    private final VideoProperties videoProperties;
    private final ProcessExecutor processExecutor;
    private final ObjectMapper objectMapper;
    private final StorageService storageService;

    /**
     * 下载视频并提取音频。
     *
     * @param url    视频链接
     * @param taskId 任务 ID（用于隔离工作目录）
     */
    public DownloadResult download(String url, String taskId) {
        Path taskDir = storageService.ensureTaskDir(taskId);

        // 1. 拉取元数据（标题、时长）
        VideoMeta meta = fetchMeta(url);

        int maxDuration = videoProperties.getDownload().getMaxDurationSeconds();
        if (maxDuration > 0 && meta.getDurationSeconds() != null && meta.getDurationSeconds() > maxDuration) {
            throw new BusinessException("视频时长超过限制（" + maxDuration + "s）: " + meta.getDurationSeconds() + "s");
        }

        // 2. 下载音视频（限分辨率 + 分片并发，加速转录场景）
        //    --ffmpeg-location：避免 IDE/JVM PATH 无 ffmpeg 时无法 merge
        String format = buildFormatSelector();
        int concurrent = Math.max(1, videoProperties.getDownload().getConcurrentFragments());
        Path videoTemplate = taskDir.resolve("video.%(ext)s");

        List<String> downloadCmd = new ArrayList<>();
        downloadCmd.add(videoProperties.getYtDlpPath());
        downloadCmd.add("-f");
        downloadCmd.add(format);
        downloadCmd.add("--merge-output-format");
        downloadCmd.add("mp4");
        downloadCmd.add("--ffmpeg-location");
        downloadCmd.add(resolveFfmpegDir());
        // HLS/DASH 多分片并行下载
        downloadCmd.add("-N");
        downloadCmd.add(String.valueOf(concurrent));
        // 略减无关开销
        downloadCmd.add("--no-playlist");
        downloadCmd.add("--no-write-playlist-metafiles");
        downloadCmd.add("--retries");
        downloadCmd.add("3");
        downloadCmd.add("--fragment-retries");
        downloadCmd.add("5");
        downloadCmd.add("-o");
        downloadCmd.add(videoTemplate.toAbsolutePath().toString());
        downloadCmd.add(url);

        log.info("yt-dlp 下载: format={}, concurrentFragments={}, url={}", format, concurrent, url);
        processExecutor.execute(downloadCmd, videoProperties.getDownload().getTimeoutSeconds());

        // 3. 解析下载产物：可能是已合并 video.mp4，也可能是 video.fXXX.mp4 + video.fYYY.m4a
        MediaBundle bundle = resolveDownloadedMedia(taskDir);
        Path videoFile = bundle.getVideoFile();
        Path audioSource = bundle.getAudioSource();

        if (videoFile == null && audioSource == null) {
            throw new BusinessException("下载完成但未找到视频/音频文件");
        }

        // 若只有分轨未合并，用本机 ffmpeg 合成可播放 mp4
        if (videoFile != null && audioSource != null && !bundle.isAlreadyMerged()) {
            Path merged = taskDir.resolve("video.mp4");
            mergeAv(videoFile, audioSource, merged);
            videoFile = merged;
            log.info("已合并分轨为: {}", merged);
        }

        if (videoFile == null) {
            // 仅音频场景：仍可转录，视频路径为空
            log.warn("仅下载到音频，无视频轨: taskId={}", taskId);
        }

        // 4. 提取/转换音频为配置格式（供 Whisper）
        String audioFormat = videoProperties.getDownload().getAudioFormat();
        Path audioFile = taskDir.resolve("audio." + audioFormat);
        extractAudio(audioSource != null ? audioSource : videoFile, audioFile, audioFormat);

        if (!Files.exists(audioFile) || fileSize(audioFile) == 0) {
            throw new BusinessException("音频提取失败，文件不存在或为空: " + audioFile);
        }

        DownloadResult result = new DownloadResult();
        result.setTitle(meta.getTitle());
        result.setDurationSeconds(meta.getDurationSeconds());
        result.setVideoPath(videoFile != null ? videoFile.toAbsolutePath().toString() : null);
        result.setAudioPath(audioFile.toAbsolutePath().toString());
        result.setTaskDir(taskDir.toAbsolutePath().toString());
        log.info("下载完成: title={}, duration={}s, video={}, audio={}",
                result.getTitle(), result.getDurationSeconds(), result.getVideoPath(), result.getAudioPath());
        return result;
    }

    /**
     * 仅用 yt-dlp 获取元数据，不下载完整媒体。
     */
    public VideoMeta fetchMeta(String url) {
        List<String> cmd = new ArrayList<>();
        cmd.add(videoProperties.getYtDlpPath());
        cmd.add("--dump-json");
        cmd.add("--no-playlist");
        cmd.add("--skip-download");
        cmd.add(url);

        String output = processExecutor.execute(cmd, 60);
        try {
            String jsonLine = output.lines()
                    .map(String::trim)
                    .filter(l -> l.startsWith("{"))
                    .findFirst()
                    .orElse(output.trim());
            JsonNode node = objectMapper.readTree(jsonLine);
            VideoMeta meta = new VideoMeta();
            meta.setTitle(node.path("title").asText("未知标题"));
            if (node.has("duration") && !node.path("duration").isNull()) {
                meta.setDurationSeconds(node.path("duration").asDouble());
            }
            meta.setId(node.path("id").asText(null));
            return meta;
        } catch (Exception e) {
            log.warn("解析 yt-dlp 元数据失败，使用默认标题: {}", e.getMessage());
            VideoMeta meta = new VideoMeta();
            meta.setTitle("未知标题");
            return meta;
        }
    }

    /**
     * 从下载目录识别：已合并文件 / 视频轨 / 音频轨。
     */
    MediaBundle resolveDownloadedMedia(Path taskDir) {
        List<Path> files = listFiles(taskDir);
        if (files.isEmpty()) {
            return new MediaBundle(null, null, false);
        }

        // 优先：yt-dlp 合并成功后的 video.mp4 / video.webm 等（不含 .f 分轨 id）
        Path merged = files.stream()
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return name.matches("video\\.(mp4|webm|mkv|mov)")
                            && !name.contains(".f");
                })
                .max(Comparator.comparingLong(this::fileSize))
                .orElse(null);

        if (merged != null) {
            log.info("找到已合并媒体: {}", merged.getFileName());
            return new MediaBundle(merged, merged, true);
        }

        // 分轨：video.f100026.mp4（纯视频）+ video.f30280.m4a（纯音频）
        List<Path> videoTracks = files.stream()
                .filter(p -> isLikelyVideoOnly(p.getFileName().toString()))
                .sorted(Comparator.comparingLong(this::fileSize).reversed())
                .collect(Collectors.toList());

        List<Path> audioTracks = files.stream()
                .filter(p -> isLikelyAudioOnly(p.getFileName().toString()))
                .sorted(Comparator.comparingLong(this::fileSize).reversed())
                .collect(Collectors.toList());

        Path videoTrack = videoTracks.isEmpty() ? null : videoTracks.get(0);
        Path audioTrack = audioTracks.isEmpty() ? null : audioTracks.get(0);

        if (videoTrack != null || audioTrack != null) {
            log.info("检测到分轨下载: video={}, audio={}",
                    videoTrack != null ? videoTrack.getFileName() : null,
                    audioTrack != null ? audioTrack.getFileName() : null);
            return new MediaBundle(videoTrack, audioTrack, false);
        }

        // 兜底：最大文件当视频
        Path largest = files.stream()
                .filter(p -> !p.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("audio."))
                .max(Comparator.comparingLong(this::fileSize))
                .orElse(null);
        log.warn("未识别标准命名，使用最大文件: {}", largest);
        return new MediaBundle(largest, largest, true);
    }

    private void mergeAv(Path videoTrack, Path audioTrack, Path output) {
        // -c copy 足够把分轨封装进 mp4；若编码不兼容再回退转码
        List<String> cmd = new ArrayList<>();
        cmd.add(videoProperties.getFfmpegPath());
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(videoTrack.toAbsolutePath().toString());
        cmd.add("-i");
        cmd.add(audioTrack.toAbsolutePath().toString());
        cmd.add("-c");
        cmd.add("copy");
        cmd.add("-map");
        cmd.add("0:v:0");
        cmd.add("-map");
        cmd.add("1:a:0");
        cmd.add("-shortest");
        cmd.add(output.toAbsolutePath().toString());

        try {
            processExecutor.execute(cmd, videoProperties.getDownload().getTimeoutSeconds());
        } catch (BusinessException e) {
            log.warn("无损合并失败，尝试重编码: {}", e.getMessage());
            List<String> reencode = new ArrayList<>();
            reencode.add(videoProperties.getFfmpegPath());
            reencode.add("-y");
            reencode.add("-i");
            reencode.add(videoTrack.toAbsolutePath().toString());
            reencode.add("-i");
            reencode.add(audioTrack.toAbsolutePath().toString());
            reencode.add("-c:v");
            reencode.add("libx264");
            reencode.add("-c:a");
            reencode.add("aac");
            reencode.add("-map");
            reencode.add("0:v:0");
            reencode.add("-map");
            reencode.add("1:a:0");
            reencode.add("-shortest");
            reencode.add(output.toAbsolutePath().toString());
            processExecutor.execute(reencode, videoProperties.getDownload().getTimeoutSeconds());
        }

        if (!Files.exists(output) || fileSize(output) == 0) {
            throw new BusinessException("音视频合并失败: " + output);
        }
    }

    /**
     * 从含音轨的源文件提取/转换为目标音频格式。
     */
    private void extractAudio(Path source, Path audioFile, String audioFormat) {
        if (source == null) {
            throw new BusinessException("无可用音频源文件");
        }

        String sourceName = source.getFileName().toString().toLowerCase(Locale.ROOT);
        // 源已是目标格式：直接复制
        if (sourceName.endsWith("." + audioFormat.toLowerCase(Locale.ROOT))) {
            try {
                Files.copy(source, audioFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.info("音频源已是 {}，直接复制: {}", audioFormat, source.getFileName());
                return;
            } catch (IOException e) {
                throw new BusinessException("复制音频失败: " + e.getMessage());
            }
        }

        List<String> ffmpegCmd = new ArrayList<>();
        ffmpegCmd.add(videoProperties.getFfmpegPath());
        ffmpegCmd.add("-y");
        ffmpegCmd.add("-i");
        ffmpegCmd.add(source.toAbsolutePath().toString());
        ffmpegCmd.add("-vn");
        ffmpegCmd.add("-map");
        ffmpegCmd.add("0:a:0");
        if ("mp3".equalsIgnoreCase(audioFormat)) {
            ffmpegCmd.add("-acodec");
            ffmpegCmd.add("libmp3lame");
            ffmpegCmd.add("-q:a");
            ffmpegCmd.add("2");
        } else if ("m4a".equalsIgnoreCase(audioFormat) || "aac".equalsIgnoreCase(audioFormat)) {
            ffmpegCmd.add("-acodec");
            ffmpegCmd.add("aac");
            ffmpegCmd.add("-b:a");
            ffmpegCmd.add("192k");
        } else {
            ffmpegCmd.add("-acodec");
            ffmpegCmd.add("copy");
        }
        ffmpegCmd.add(audioFile.toAbsolutePath().toString());

        processExecutor.execute(ffmpegCmd, videoProperties.getDownload().getTimeoutSeconds());
    }

    /**
     * 拼装 yt-dlp -f 格式选择器。
     * <p>默认策略（加速转录场景）：
     * <ul>
     *   <li>限制高度 ≤ maxHeight（默认 720p，体积远小于 1080p/4K）</li>
     *   <li>preferMerged 时优先单文件/已封装流，少一次双轨下载+合并</li>
     *   <li>回退到分轨再合并，最后不限清晰度保证能下到</li>
     * </ul>
     */
    String buildFormatSelector() {
        VideoProperties.Download cfg = videoProperties.getDownload();
        if (cfg.getFormat() != null && !cfg.getFormat().isBlank()) {
            return cfg.getFormat().trim();
        }

        int h = cfg.getMaxHeight();
        if (h <= 0) {
            // 不限分辨率：原逻辑
            return "bv*+ba/b";
        }

        // height 过滤：bv / ba / progressive best
        String hFilter = "[height<=?" + h + "]";
        if (cfg.isPreferMerged()) {
            // 1) 单文件且 ≤maxHeight（最快）
            // 2) 分轨视频≤maxHeight + 最佳音轨
            // 3) 任意 ≤maxHeight
            // 4) 再放宽到不限高度，避免平台无 720 档失败
            return "b" + hFilter + "[ext=mp4]"
                    + "/b" + hFilter
                    + "/bv*" + hFilter + "+ba/b"
                    + "/bv*+ba/b";
        }
        return "bv*" + hFilter + "+ba/b"
                + "/bv*+ba/b";
    }

    /**
     * yt-dlp 的 --ffmpeg-location 需要「目录」或可执行文件路径；传目录更稳妥。
     */
    private String resolveFfmpegDir() {
        Path ffmpeg = Path.of(videoProperties.getFfmpegPath()).toAbsolutePath().normalize();
        if (Files.isRegularFile(ffmpeg)) {
            Path parent = ffmpeg.getParent();
            return parent != null ? parent.toString() : ffmpeg.toString();
        }
        return ffmpeg.toString();
    }

    private static boolean isLikelyVideoOnly(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        // yt-dlp 分轨：video.f100026.mp4
        if (lower.matches("video\\.f\\d+\\.(mp4|webm|mkv|flv|ts)")) {
            return true;
        }
        // 纯扩展名视频，且不是 audio.*
        return lower.startsWith("video.")
                && (lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mkv")
                || lower.endsWith(".flv") || lower.endsWith(".mov"))
                && !lower.contains(".m4a") && !lower.endsWith(".mp3") && !lower.endsWith(".aac");
    }

    private static boolean isLikelyAudioOnly(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.matches("video\\.f\\d+\\.(m4a|webm|mp3|aac|opus|ogg|wav)")) {
            return true;
        }
        return lower.endsWith(".m4a") || lower.endsWith(".mp3") || lower.endsWith(".aac")
                || lower.endsWith(".opus") || lower.endsWith(".ogg") || lower.endsWith(".wav")
                || (lower.startsWith("video.") && (lower.contains("audio") || lower.endsWith(".m4a")));
    }

    private List<Path> listFiles(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile).collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    @Data
    static class MediaBundle {
        private final Path videoFile;
        /** 含音轨的文件：合并后的视频，或单独的 m4a/mp3 */
        private final Path audioSource;
        private final boolean alreadyMerged;
    }

    @Data
    public static class DownloadResult {
        private String title;
        private Double durationSeconds;
        private String videoPath;
        private String audioPath;
        private String taskDir;
    }

    @Data
    public static class VideoMeta {
        private String id;
        private String title;
        private Double durationSeconds;
    }
}
