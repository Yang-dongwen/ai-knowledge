package com.dwcode.okxbot.video.service;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.storage.LocalObjectStorage;
import com.dwcode.okxbot.storage.ObjectKeyBuilder;
import com.dwcode.okxbot.storage.ObjectMeta;
import com.dwcode.okxbot.storage.ObjectStoragePort;
import com.dwcode.okxbot.storage.ScratchWorkspace;
import com.dwcode.okxbot.storage.config.StorageProperties;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.entity.VideoTaskEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 视频任务路径与持久化（PR3：scratch 处理 + ObjectStorage 持久）。
 *
 * <p>处理中目录：{@code storage.scratch}/{@code video}/{taskId}/
 * <p>持久对象：{@code {env}/video/{userId}/{taskId}/...}（local 或 R2）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    public static final String MODULE = "video";

    private final VideoProperties videoProperties;
    private final ObjectMapper objectMapper;
    private final ObjectStoragePort objectStorage;
    private final ObjectKeyBuilder keyBuilder;
    private final ScratchWorkspace scratchWorkspace;
    private final StorageProperties storageProperties;

    /**
     * 任务处理目录（scratch，始终本地）。
     */
    public Path resolveTaskDir(String taskId) {
        return scratchWorkspace.resolveTaskScratch(MODULE, taskId);
    }

    public Path ensureTaskDir(String taskId) {
        return scratchWorkspace.openTaskScratch(MODULE, taskId);
    }

    public Path resolveVideoPath(String taskId, String extension) {
        String ext = (extension == null || extension.isBlank()) ? "mp4" : extension.replace(".", "");
        return resolveTaskDir(taskId).resolve("video." + ext);
    }

    public Path resolveAudioPath(String taskId) {
        String format = videoProperties.getDownload().getAudioFormat();
        return resolveTaskDir(taskId).resolve("audio." + format);
    }

    public Path resolveTranscriptionPath(String taskId) {
        return resolveTaskDir(taskId).resolve("transcription.json");
    }

    public Path resolveSummaryPath(String taskId) {
        return resolveTaskDir(taskId).resolve("summary.json");
    }

    public Path resolveVisualPath(String taskId) {
        return resolveTaskDir(taskId).resolve("visual_understanding.json");
    }

    /**
     * 将对象序列化为本地 JSON（scratch）。
     *
     * @return 本地绝对路径
     */
    public String saveJson(Path path, Object data) {
        try {
            Files.createDirectories(path.getParent());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            Files.writeString(path, json, StandardCharsets.UTF_8);
            log.info("已保存 JSON 文件: {}", path);
            return path.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new BusinessException("保存 JSON 文件失败: " + path + " — " + e.getMessage());
        }
    }

    /**
     * 任务成功：把 durable 产物 put 到对象存储，路径字段改为 object key，再清 scratch。
     */
    public void persistAndCleanupAfterSuccess(VideoTaskEntity task) {
        if (task == null || task.getId() == null) {
            return;
        }
        long userId = effectiveUserId(task);
        String taskId = String.valueOf(task.getId());
        try {
            // 视频：优先 browser 转码版
            Path videoLocal = resolveExistingLocalFile(task.getVideoPath());
            if (videoLocal != null) {
                Path browser = videoLocal.resolveSibling("video.browser.mp4");
                Path upload = Files.isRegularFile(browser) ? browser : videoLocal;
                task.setVideoPath(publishFile(userId, taskId, upload, upload.getFileName().toString()));
            } else if (task.getVideoPath() != null && !ObjectKeyBuilder.looksLikeLocalAbsolutePath(task.getVideoPath())) {
                // 已是 key
                log.debug("videoPath 已是 object key: {}", task.getVideoPath());
            }

            Path audioLocal = resolveExistingLocalFile(task.getAudioPath());
            if (audioLocal != null) {
                task.setAudioPath(publishFile(userId, taskId, audioLocal, audioLocal.getFileName().toString()));
            }

            task.setTranscriptionPath(publishIfLocal(userId, taskId, task.getTranscriptionPath(), "transcription.json"));
            task.setSummaryPath(publishIfLocal(userId, taskId, task.getSummaryPath(), "summary.json"));
            task.setVisualPath(publishIfLocal(userId, taskId, task.getVisualPath(), "visual_understanding.json"));

            // 扫 scratch 中可能遗漏的标准文件名（如仅 browser、无 path 字段）
            publishStandardScratchFiles(userId, taskId, task);

            log.info("任务产物已持久化到对象存储: taskId={}, provider={}, video={}",
                    taskId, objectStorage.providerId(), task.getVideoPath());
        } catch (Exception e) {
            throw new BusinessException("上传任务产物失败: " + e.getMessage());
        } finally {
            if (storageProperties.getCleanup().isScratchOnSuccess()) {
                scratchWorkspace.cleanupScratch(MODULE, taskId);
                deleteLegacyWorkDir(taskId);
            }
        }
    }

    /**
     * 任务失败 / 暂停中断：清 scratch；可选清理未完成 object 前缀。
     */
    public void cleanupAfterFailure(VideoTaskEntity task) {
        if (task == null || task.getId() == null) {
            return;
        }
        String taskId = String.valueOf(task.getId());
        if (storageProperties.getCleanup().isScratchOnFailure()) {
            scratchWorkspace.cleanupScratch(MODULE, taskId);
            deleteLegacyWorkDir(taskId);
        }
        if (storageProperties.getCleanup().isR2OnFailure()
                && task.getUserId() != null
                && !hasDurableMarkers(task)) {
            // 仅当尚未成功持久化时清理 prefix，避免误删成功任务
            try {
                String prefix = keyBuilder.taskPrefix(MODULE, effectiveUserId(task), taskId);
                int n = objectStorage.deletePrefix(prefix);
                if (n > 0) {
                    log.info("失败任务已清理对象前缀: taskId={}, deleted≈{}", taskId, n);
                }
            } catch (Exception e) {
                log.warn("失败任务清理对象前缀忽略: taskId={} — {}", taskId, e.getMessage());
            }
        }
    }

    private boolean hasDurableMarkers(VideoTaskEntity task) {
        // SUCCESS 后路径为 object key；失败中途可能是本地路径
        return task.getVideoPath() != null
                && !task.getVideoPath().isBlank()
                && !ObjectKeyBuilder.looksLikeLocalAbsolutePath(task.getVideoPath())
                && objectStorage.exists(task.getVideoPath());
    }

    public String publishFile(long userId, String taskId, Path localFile, String relativeName) {
        if (localFile == null || !Files.isRegularFile(localFile)) {
            throw new BusinessException(400, "待上传文件不存在: " + localFile);
        }
        String key = keyBuilder.build(MODULE, userId, taskId, relativeName);
        String ct = LocalObjectStorage.guessContentType(relativeName);
        objectStorage.put(key, localFile, ct);
        return key;
    }

    private String publishIfLocal(long userId, String taskId, String pathOrKey, String defaultRelative) {
        Path local = resolveExistingLocalFile(pathOrKey);
        if (local == null) {
            return pathOrKey;
        }
        String rel = local.getFileName() != null ? local.getFileName().toString() : defaultRelative;
        return publishFile(userId, taskId, local, rel);
    }

    private void publishStandardScratchFiles(long userId, String taskId, VideoTaskEntity task) {
        Path dir = resolveTaskDir(taskId);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> list = Files.list(dir)) {
            for (Path p : list.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mp3")
                        || lower.endsWith(".m4a") || lower.endsWith(".json"))) {
                    continue;
                }
                // 已有 key 且同名则跳过重复 put（可覆盖写，幂等）
                try {
                    String key = publishFile(userId, taskId, p, name);
                    if (lower.startsWith("video") && (task.getVideoPath() == null
                            || ObjectKeyBuilder.looksLikeLocalAbsolutePath(task.getVideoPath()))) {
                        // 优先 browser
                        if (lower.contains("browser") || task.getVideoPath() == null
                                || !String.valueOf(task.getVideoPath()).contains("browser")) {
                            if (lower.contains("browser") || !Files.isRegularFile(dir.resolve("video.browser.mp4"))) {
                                task.setVideoPath(key);
                            }
                        }
                    }
                    if (lower.startsWith("audio") && (task.getAudioPath() == null
                            || ObjectKeyBuilder.looksLikeLocalAbsolutePath(task.getAudioPath()))) {
                        task.setAudioPath(key);
                    }
                    if (lower.equals("transcription.json")) {
                        task.setTranscriptionPath(key);
                    }
                    if (lower.equals("summary.json")) {
                        task.setSummaryPath(key);
                    }
                    if (lower.equals("visual_understanding.json")) {
                        task.setVisualPath(key);
                    }
                } catch (Exception e) {
                    log.warn("扫描上传跳过 {}: {}", name, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("扫描 scratch 失败: {}", e.getMessage());
        }
        // 若同时有 video.mp4 与 video.browser.mp4，强制 videoPath 指向 browser key
        try {
            String browserKey = keyBuilder.build(MODULE, userId, taskId, "video.browser.mp4");
            if (objectStorage.exists(browserKey)) {
                task.setVideoPath(browserKey);
            }
        } catch (Exception ignored) {
            // ignore
        }
    }

    /**
     * 删除任务全部存储：scratch + 旧 work-dir + 对象前缀。
     */
    public int deleteTaskStorage(Long userId, String taskId) {
        int deleted = 0;
        deleted += scratchWorkspace.cleanupScratch(MODULE, taskId);
        deleted += deleteLegacyWorkDir(taskId);
        long uid = userId != null && userId > 0 ? userId : 0L;
        try {
            deleted += objectStorage.deletePrefix(keyBuilder.taskPrefix(MODULE, uid, taskId));
        } catch (Exception e) {
            log.warn("删除对象前缀失败: taskId={} — {}", taskId, e.getMessage());
        }
        // 兼容历史：再扫 userId=0
        if (uid != 0L) {
            try {
                deleted += objectStorage.deletePrefix(keyBuilder.taskPrefix(MODULE, 0L, taskId));
            } catch (Exception ignored) {
                // ignore
            }
        }
        return deleted;
    }

    /** @deprecated 使用 {@link #deleteTaskStorage} */
    public int deleteTaskDir(String taskId) {
        return deleteTaskStorage(null, taskId);
    }

    private int deleteLegacyWorkDir(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return 0;
        }
        Path dir = Path.of(videoProperties.getWorkDir(), taskId).toAbsolutePath().normalize();
        Path workRoot = Path.of(videoProperties.getWorkDir()).toAbsolutePath().normalize();
        if (!dir.startsWith(workRoot) || dir.equals(workRoot)) {
            return 0;
        }
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int deleted = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    if (Files.deleteIfExists(p)) {
                        deleted++;
                    }
                } catch (IOException e) {
                    log.warn("删除遗留 work-dir 文件失败: {} — {}", p, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("遍历遗留 work-dir 失败: {} — {}", dir, e.getMessage());
        }
        return deleted;
    }

    public boolean mediaAvailable(String pathOrKey) {
        if (pathOrKey == null || pathOrKey.isBlank()) {
            return false;
        }
        if (ObjectKeyBuilder.looksLikeLocalAbsolutePath(pathOrKey)) {
            try {
                Path p = Path.of(pathOrKey);
                if (Files.isRegularFile(p) && Files.size(p) > 0) {
                    return true;
                }
                Path browser = p.resolveSibling("video.browser.mp4");
                return Files.isRegularFile(browser) && Files.size(browser) > 0;
            } catch (Exception e) {
                return false;
            }
        }
        // object key
        if (objectStorage.exists(pathOrKey)) {
            return true;
        }
        // 尝试 sibling browser key
        String browserKey = siblingBrowserKey(pathOrKey);
        return browserKey != null && objectStorage.exists(browserKey);
    }

    public Optional<ObjectMeta> headMedia(String pathOrKey) {
        if (pathOrKey == null || pathOrKey.isBlank()) {
            return Optional.empty();
        }
        if (ObjectKeyBuilder.looksLikeLocalAbsolutePath(pathOrKey)) {
            Path p = Path.of(pathOrKey);
            try {
                if (!Files.isRegularFile(p)) {
                    Path browser = p.resolveSibling("video.browser.mp4");
                    if (Files.isRegularFile(browser)) {
                        p = browser;
                    } else {
                        return Optional.empty();
                    }
                }
                return Optional.of(ObjectMeta.builder()
                        .key(pathOrKey)
                        .sizeBytes(Files.size(p))
                        .contentType(guessMediaType(p))
                        .lastModifiedEpochMs(Files.getLastModifiedTime(p).toMillis())
                        .build());
            } catch (IOException e) {
                return Optional.empty();
            }
        }
        Optional<ObjectMeta> meta = objectStorage.head(pathOrKey);
        if (meta.isPresent()) {
            return meta;
        }
        String browserKey = siblingBrowserKey(pathOrKey);
        if (browserKey != null) {
            return objectStorage.head(browserKey);
        }
        return Optional.empty();
    }

    public InputStream openMediaStream(String pathOrKey) {
        return openMediaStream(pathOrKey, 0L, Long.MAX_VALUE - 1);
    }

    /**
     * 打开媒体流（本地路径或 object key），支持字节区间（HTTP Range）。
     */
    public InputStream openMediaStream(String pathOrKey, long startInclusive, long endInclusive) {
        if (pathOrKey == null || pathOrKey.isBlank()) {
            throw new BusinessException(404, "媒体路径为空");
        }
        if (ObjectKeyBuilder.looksLikeLocalAbsolutePath(pathOrKey)) {
            Path p = requireExistingFile(pathOrKey, "媒体文件");
            try {
                long size = Files.size(p);
                long start = Math.max(0, startInclusive);
                if (start >= size) {
                    throw new BusinessException(416, "Requested Range Not Satisfiable");
                }
                long end = Math.min(endInclusive, size - 1);
                long len = end - start + 1;
                InputStream in = Files.newInputStream(p);
                if (start > 0) {
                    in.skipNBytes(start);
                }
                return new com.dwcode.okxbot.common.web.LimitedInputStream(in, len);
            } catch (BusinessException e) {
                throw e;
            } catch (IOException e) {
                throw new BusinessException("打开本地媒体失败: " + e.getMessage());
            }
        }
        String key = pathOrKey;
        if (!objectStorage.exists(key)) {
            String browserKey = siblingBrowserKey(pathOrKey);
            if (browserKey != null && objectStorage.exists(browserKey)) {
                key = browserKey;
            } else {
                throw new BusinessException(404, "对象不存在: " + pathOrKey);
            }
        }
        if (startInclusive <= 0 && endInclusive >= Long.MAX_VALUE / 2) {
            return objectStorage.openStream(key);
        }
        return objectStorage.openStream(key, startInclusive, endInclusive);
    }

    /**
     * 将 object key 下载到 scratch 临时文件（供 ffmpeg 转码等）。
     * <p>调用方用完后应 {@link #cleanupScratchOnly(String)}，避免打开页面留下长期缓存。
     */
    public Path materializeToScratch(String pathOrKey, String taskId, String localName) {
        if (ObjectKeyBuilder.looksLikeLocalAbsolutePath(pathOrKey)) {
            return requireExistingFile(pathOrKey, "媒体文件");
        }
        Path dest = ensureTaskDir(taskId).resolve(localName != null ? localName : "materialized.bin");
        objectStorage.getToFile(pathOrKey, dest);
        return dest;
    }

    /** 仅清理该任务 video scratch（不删 R2 / 旧 work-dir）。 */
    public int cleanupScratchOnly(String taskId) {
        return scratchWorkspace.cleanupScratch(MODULE, taskId);
    }

    public Path requireExistingFile(String absolutePath, String label) {
        if (absolutePath == null || absolutePath.isBlank()) {
            throw new BusinessException(404, label + "路径为空");
        }
        if (!ObjectKeyBuilder.looksLikeLocalAbsolutePath(absolutePath)) {
            // object key：物化到临时
            throw new BusinessException(400, label + "为对象 key，请使用 openMediaStream/materializeToScratch");
        }
        Path path = Path.of(absolutePath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new BusinessException(404, label + "不存在: " + path);
        }
        return path;
    }

    private Path resolveExistingLocalFile(String pathOrKey) {
        if (pathOrKey == null || pathOrKey.isBlank()) {
            return null;
        }
        if (!ObjectKeyBuilder.looksLikeLocalAbsolutePath(pathOrKey)) {
            return null;
        }
        try {
            Path p = Path.of(pathOrKey);
            return Files.isRegularFile(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String siblingBrowserKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        int slash = key.lastIndexOf('/');
        String name = slash >= 0 ? key.substring(slash + 1) : key;
        String prefix = slash >= 0 ? key.substring(0, slash + 1) : "";
        if ("video.browser.mp4".equals(name)) {
            return key;
        }
        if (name.startsWith("video.")) {
            return prefix + "video.browser.mp4";
        }
        return null;
    }

    public static long effectiveUserId(VideoTaskEntity task) {
        if (task != null && task.getUserId() != null && task.getUserId() > 0) {
            return task.getUserId();
        }
        return 0L;
    }

    public String detectPlatform(String url) {
        if (url == null || url.isBlank()) {
            return "other";
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("douyin.com") || lower.contains("iesdouyin.com") || lower.contains("tiktok.com")) {
            return "douyin";
        }
        if (lower.contains("bilibili.com") || lower.contains("b23.tv")) {
            return "bilibili";
        }
        if (lower.contains("youtube.com") || lower.contains("youtu.be")) {
            return "youtube";
        }
        if (lower.contains("xiaohongshu.com") || lower.contains("xhslink.com")) {
            return "xiaohongshu";
        }
        return "other";
    }

    public String guessMediaType(Path path) {
        if (path == null || path.getFileName() == null) {
            return "application/octet-stream";
        }
        return LocalObjectStorage.guessContentType(path.getFileName().toString());
    }

    public ObjectStoragePort objectStorage() {
        return objectStorage;
    }

    public ObjectKeyBuilder keyBuilder() {
        return keyBuilder;
    }
}
