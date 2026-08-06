package com.dwcode.okxbot.video.service;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.util.ProcessExecutor;
import com.dwcode.okxbot.video.util.VideoUrlNormalizer;
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
        return download(url, taskId, true);
    }

    /**
     * @param extractAudio false 时跳过抽音频（仅下载视频场景）
     */
    public DownloadResult download(String url, String taskId, boolean extractAudio) {
        Path taskDir = storageService.ensureTaskDir(taskId);
        String resolvedUrl = VideoUrlNormalizer.normalize(url);
        if (resolvedUrl == null || resolvedUrl.isBlank()) {
            throw new BusinessException(400, "视频链接不能为空");
        }
        VideoUrlNormalizer.assertSafeForDownload(resolvedUrl);
        if (!resolvedUrl.equals(url)) {
            log.info("已规范化视频链接: {} → {}", url, resolvedUrl);
        }
        Long taskIdLong = parseTaskId(taskId);
        processExecutor.bindTask(taskIdLong);
        try {
            return downloadBound(url, resolvedUrl, taskDir, extractAudio);
        } finally {
            processExecutor.clearTask();
        }
    }

    private DownloadResult downloadBound(String url, String resolvedUrl, Path taskDir, boolean extractAudio) {
        // 1. 拉取元数据（标题、时长）
        VideoMeta meta = fetchMeta(resolvedUrl);

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
        appendCookieArgs(downloadCmd, resolvedUrl);
        appendExtraArgs(downloadCmd);
        downloadCmd.add("-o");
        downloadCmd.add(videoTemplate.toAbsolutePath().toString());
        downloadCmd.add(resolvedUrl);

        log.info("yt-dlp 下载: format={}, concurrentFragments={}, url={}", format, concurrent, resolvedUrl);
        try {
            processExecutor.execute(downloadCmd, videoProperties.getDownload().getTimeoutSeconds());
        } catch (BusinessException e) {
            throw enrichDownloadError(resolvedUrl, e);
        }

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
            log.warn("仅下载到音频，无视频轨: dir={}", taskDir);
        } else {
            // 抖音等常下到 HEVC，Chrome/Edge 无法 <video> 播放 → 转成 H.264
            videoFile = ensureBrowserPlayable(videoFile);
        }

        String audioPath = null;
        if (extractAudio) {
            // 4. 提取/转换音频为配置格式（供 Whisper）
            String audioFormat = videoProperties.getDownload().getAudioFormat();
            Path audioFile = taskDir.resolve("audio." + audioFormat);
            extractAudio(audioSource != null ? audioSource : videoFile, audioFile, audioFormat);

            if (!Files.exists(audioFile) || fileSize(audioFile) == 0) {
                throw new BusinessException("音频提取失败，文件不存在或为空: " + audioFile);
            }
            audioPath = audioFile.toAbsolutePath().toString();
        } else {
            log.info("跳过音频提取（仅下载视频）: dir={}", taskDir);
            if (videoFile == null) {
                throw new BusinessException("仅下载模式未得到视频文件");
            }
        }

        DownloadResult result = new DownloadResult();
        result.setTitle(meta.getTitle());
        result.setDurationSeconds(meta.getDurationSeconds());
        result.setVideoPath(videoFile != null ? videoFile.toAbsolutePath().toString() : null);
        result.setAudioPath(audioPath);
        result.setTaskDir(taskDir.toAbsolutePath().toString());
        log.info("下载完成: title={}, duration={}s, video={}, audio={}",
                result.getTitle(), result.getDurationSeconds(), result.getVideoPath(), result.getAudioPath());
        return result;
    }

    /**
     * 仅用 yt-dlp 获取元数据，不下载完整媒体。
     */
    public VideoMeta fetchMeta(String url) {
        String resolvedUrl = VideoUrlNormalizer.normalize(url);
        if (resolvedUrl == null || resolvedUrl.isBlank()) {
            throw new BusinessException(400, "视频链接不能为空");
        }
        VideoUrlNormalizer.assertSafeForDownload(resolvedUrl);
        List<String> cmd = new ArrayList<>();
        cmd.add(videoProperties.getYtDlpPath());
        cmd.add("--dump-json");
        cmd.add("--no-playlist");
        cmd.add("--skip-download");
        appendCookieArgs(cmd, resolvedUrl);
        appendExtraArgs(cmd);
        cmd.add(resolvedUrl);

        String output;
        try {
            output = processExecutor.execute(cmd, 60);
        } catch (BusinessException e) {
            throw enrichDownloadError(resolvedUrl, e);
        }
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
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("解析 yt-dlp 元数据失败，使用默认标题: {}", e.getMessage());
            VideoMeta meta = new VideoMeta();
            meta.setTitle("未知标题");
            return meta;
        }
    }

    /**
     * 追加 Cookie 相关参数。cookiesFile 优先于 cookiesFromBrowser。
     * 仅对抖音 / TikTok / 小红书附加，避免浏览器 Cookie 库被锁时拖垮 B 站等。
     */
    void appendCookieArgs(List<String> cmd, String url) {
        if (!platformLikelyNeedsCookies(url)) {
            return;
        }
        VideoProperties.Download cfg = videoProperties.getDownload();
        if (cfg.getCookiesFile() != null && !cfg.getCookiesFile().isBlank()) {
            Path p = Path.of(cfg.getCookiesFile().trim());
            if (!Files.isRegularFile(p)) {
                log.warn("video.download.cookies-file 不存在，已忽略: {}", p.toAbsolutePath());
            } else {
                cmd.add("--cookies");
                cmd.add(p.toAbsolutePath().toString());
            }
            return;
        }
        if (cfg.getCookiesFromBrowser() != null && !cfg.getCookiesFromBrowser().isBlank()) {
            cmd.add("--cookies-from-browser");
            cmd.add(cfg.getCookiesFromBrowser().trim());
        }
    }

    static boolean platformLikelyNeedsCookies(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("douyin.com")
                || lower.contains("iesdouyin.com")
                || lower.contains("tiktok.com")
                || lower.contains("xiaohongshu.com")
                || lower.contains("xhslink.com");
    }

    void appendExtraArgs(List<String> cmd) {
        List<String> extra = videoProperties.getDownload().getExtraArgs();
        if (extra == null || extra.isEmpty()) {
            return;
        }
        for (String arg : extra) {
            if (arg != null && !arg.isBlank()) {
                cmd.add(arg.trim());
            }
        }
    }

    /**
     * 将 yt-dlp 原始错误转成可操作的中文提示（尤其是抖音 Cookie / 链接形态）。
     */
    BusinessException enrichDownloadError(String url, BusinessException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        String lower = msg.toLowerCase(Locale.ROOT);
        boolean douyin = url != null && (url.toLowerCase(Locale.ROOT).contains("douyin.com")
                || url.toLowerCase(Locale.ROOT).contains("iesdouyin.com"));

        if (lower.contains("unsupported url")) {
            return new BusinessException(
                    "不支持的视频链接。抖音请使用「分享 → 复制链接」，或标准地址 https://www.douyin.com/video/{id}；"
                            + "个人页/喜欢页带 modal_id 的地址会自动转换。原始错误: " + truncateMsg(msg, 280));
        }
        if (lower.contains("dpapi") || lower.contains("decrypt with dpapi")
                || lower.contains("appbound") || lower.contains("app-bound")) {
            return new BusinessException(
                    "Windows 无法解密 Edge/Chrome Cookie（DPAPI/App-Bound 加密）。"
                            + "请不要用 cookies-from-browser，改用 cookies-file：\n"
                            + "1) Edge 打开 https://www.douyin.com 并刷新\n"
                            + "2) F12 → 网络 → 任意请求 → 请求标头 → 复制 Cookie 整行\n"
                            + "3) 运行 okx-bot/scripts/import-cookie-header.ps1 生成 data/douyin-cookies.txt\n"
                            + "4) application.yml 设置 video.download.cookies-file 指向该文件并重启后端。"
                            + " 原始错误: " + truncateMsg(msg, 180));
        }
        if (lower.contains("fresh cookies") || lower.contains("cookies are needed")
                || (douyin && lower.contains("cookie") && !lower.contains("could not copy"))) {
            return new BusinessException(
                    "抖音需要有效 Cookie。Windows 上请用 cookies-file（不要用 cookies-from-browser）：\n"
                            + "  Edge 打开 douyin.com → F12 复制 Cookie → 运行 scripts/import-cookie-header.ps1\n"
                            + "  video.download.cookies-file: D:/gitprojects/auto-exchange/okx-bot/data/douyin-cookies.txt\n"
                            + " 原始错误: " + truncateMsg(msg, 200));
        }
        if (lower.contains("could not copy") && lower.contains("cookie")) {
            return new BusinessException(
                    "无法复制浏览器 Cookie 库（进程占用或加密限制）。请改用 cookies-file：\n"
                            + "  运行 okx-bot/scripts/import-cookie-header.ps1 从 DevTools 导入，"
                            + "并注释掉 cookies-from-browser。原始错误: "
                            + truncateMsg(msg, 180));
        }
        return e;
    }

    private static String truncateMsg(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
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
     * <p>默认策略（加速转录 + 浏览器可播）：
     * <ul>
     *   <li>优先 H.264/AVC（Chrome/Edge 原生支持；抖音默认常给 HEVC 导致前端黑屏）</li>
     *   <li>限制高度 ≤ maxHeight（默认 720p）</li>
     *   <li>preferMerged 时优先单文件流</li>
     *   <li>最后回退任意编码，由 {@link #ensureBrowserPlayable} 再转 H.264</li>
     * </ul>
     */
    String buildFormatSelector() {
        VideoProperties.Download cfg = videoProperties.getDownload();
        if (cfg.getFormat() != null && !cfg.getFormat().isBlank()) {
            return cfg.getFormat().trim();
        }

        int h = cfg.getMaxHeight();
        // 浏览器友好：优先 avc1/h264（抖音默认 HEVC 时前端无法播）
        if (h <= 0) {
            return "bv*[vcodec^=avc]+ba/bv*[vcodec^=h264]+ba"
                    + "/b[vcodec^=avc]/b[vcodec^=h264]"
                    + "/bv*+ba/b";
        }

        String hFilter = "[height<=?" + h + "]";
        if (cfg.isPreferMerged()) {
            return "b" + hFilter + "[vcodec^=avc][ext=mp4]"
                    + "/b" + hFilter + "[vcodec^=h264][ext=mp4]"
                    + "/b" + hFilter + "[vcodec^=avc]"
                    + "/b" + hFilter + "[vcodec^=h264]"
                    + "/bv*" + hFilter + "[vcodec^=avc]+ba"
                    + "/bv*" + hFilter + "[vcodec^=h264]+ba"
                    + "/b" + hFilter + "[ext=mp4]"
                    + "/b" + hFilter
                    + "/bv*" + hFilter + "+ba/b"
                    + "/bv*+ba/b";
        }
        return "bv*" + hFilter + "[vcodec^=avc]+ba"
                + "/bv*" + hFilter + "[vcodec^=h264]+ba"
                + "/bv*" + hFilter + "+ba/b"
                + "/bv*+ba/b";
    }

    /**
     * 确保浏览器可播放：HEVC/H.265、AV1 等转码为 H.264 + AAC mp4。
     * <p>已存在且较新的 {@code video.browser.mp4} 会直接复用（服务已有任务时按需转码）。
     *
     * @return 可给前端 &lt;video&gt; 的路径（可能仍是原文件，或同目录 video.browser.mp4）
     */
    public Path ensureBrowserPlayable(Path videoFile) {
        if (videoFile == null || !Files.isRegularFile(videoFile) || fileSize(videoFile) == 0) {
            return videoFile;
        }

        Path browserCopy = videoFile.resolveSibling("video.browser.mp4");
        if (Files.isRegularFile(browserCopy) && fileSize(browserCopy) > 0) {
            String browserCodec = probeVideoCodec(browserCopy);
            if (browserCodec != null
                    && !browserCodec.equalsIgnoreCase("null")
                    && isBrowserFriendlyVideoCodec(browserCodec)) {
                // 已有 H.264 副本则优先返回（避免继续下发 HEVC/AV1 源文件）
                return browserCopy;
            }
        }

        String codec = probeVideoCodec(videoFile);
        if (isBrowserFriendlyVideoCodec(codec)) {
            log.debug("视频编码已可播: file={}, codec={}", videoFile.getFileName(), codec);
            return videoFile;
        }

        log.info("视频编码浏览器不友好(codec={})，转码 H.264: {}", codec, videoFile.getFileName());
        Path tmp = videoFile.resolveSibling("video.browser.tmp.mp4");
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(videoProperties.getFfmpegPath());
            cmd.add("-y");
            cmd.add("-i");
            cmd.add(videoFile.toAbsolutePath().toString());
            cmd.add("-map");
            cmd.add("0:v:0");
            cmd.add("-map");
            cmd.add("0:a:0?");
            cmd.add("-c:v");
            cmd.add("libx264");
            cmd.add("-preset");
            cmd.add("veryfast");
            cmd.add("-crf");
            cmd.add("23");
            cmd.add("-pix_fmt");
            cmd.add("yuv420p");
            cmd.add("-c:a");
            cmd.add("aac");
            cmd.add("-b:a");
            cmd.add("128k");
            cmd.add("-movflags");
            cmd.add("+faststart");
            cmd.add(tmp.toAbsolutePath().toString());

            processExecutor.execute(cmd, Math.max(videoProperties.getDownload().getTimeoutSeconds(), 600));

            if (!Files.exists(tmp) || fileSize(tmp) == 0) {
                log.warn("H.264 转码产出为空，仍返回原文件: {}", videoFile);
                return videoFile;
            }
            Files.move(tmp, browserCopy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("已生成浏览器可播文件: {} (codec {} → h264)", browserCopy.getFileName(), codec);
            return browserCopy;
        } catch (Exception e) {
            log.warn("H.264 转码失败，仍返回原文件: {} — {}", videoFile, e.getMessage());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // ignore
            }
            return videoFile;
        }
    }

    /**
     * ffprobe 探测首个视频流 codec_name；失败返回 null。
     */
    String probeVideoCodec(Path videoFile) {
        if (videoFile == null || !Files.isRegularFile(videoFile)) {
            return null;
        }
        try {
            Path ffprobe = resolveFfprobePath();
            List<String> cmd = new ArrayList<>();
            cmd.add(ffprobe.toString());
            cmd.add("-v");
            cmd.add("error");
            cmd.add("-select_streams");
            cmd.add("v:0");
            cmd.add("-show_entries");
            cmd.add("stream=codec_name");
            cmd.add("-of");
            cmd.add("default=nw=1:nk=1");
            cmd.add(videoFile.toAbsolutePath().toString());
            String out = processExecutor.execute(cmd, 30);
            if (out == null || out.isBlank()) {
                return null;
            }
            return out.lines()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("ffprobe 探测编码失败: {} — {}", videoFile.getFileName(), e.getMessage());
            return null;
        }
    }

    static boolean isBrowserFriendlyVideoCodec(String codec) {
        if (codec == null || codec.isBlank()) {
            // 探测失败时保守：不强制转码（避免误伤）
            return true;
        }
        String c = codec.toLowerCase(Locale.ROOT);
        // Chrome/Edge 普遍可播
        if (c.contains("h264") || c.contains("avc") || c.equals("vp8") || c.equals("vp9")) {
            return true;
        }
        // 常见浏览器不支持或支持差：hevc/h265/av1/mpeg4 等
        return false;
    }

    private Path resolveFfprobePath() {
        Path ffmpeg = Path.of(videoProperties.getFfmpegPath()).toAbsolutePath().normalize();
        Path parent = ffmpeg.getParent();
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String probeName = win ? "ffprobe.exe" : "ffprobe";
        if (parent != null) {
            Path candidate = parent.resolve(probeName);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return parent != null ? parent.resolve(probeName) : Path.of(probeName);
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

    private static Long parseTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(taskId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
