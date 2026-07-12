package com.dwcode.okxbot.video.service;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 文件存储与路径管理（v2 持久化核心）。
 *
 * <p>按 taskId 隔离目录：
 * <pre>
 * {workDir}/{taskId}/
 *   video.mp4
 *   audio.mp3
 *   transcription.json
 *   summary.json
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private final VideoProperties videoProperties;
    private final ObjectMapper objectMapper;

    /**
     * 任务根目录（绝对路径）。
     */
    public Path resolveTaskDir(String taskId) {
        return Path.of(videoProperties.getWorkDir(), taskId).toAbsolutePath().normalize();
    }

    /**
     * 确保任务目录存在。
     */
    public Path ensureTaskDir(String taskId) {
        Path dir = resolveTaskDir(taskId);
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new BusinessException("无法创建任务目录: " + e.getMessage());
        }
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

    /**
     * 将对象序列化为 JSON 文件。
     *
     * @return 写入后的绝对路径字符串
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
     * 删除任务目录及其中全部文件（视频/音频/JSON 等）。
     * 目录不存在时静默成功。
     */
    public int deleteTaskDir(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return 0;
        }
        Path dir = resolveTaskDir(taskId);
        Path workRoot = Path.of(videoProperties.getWorkDir()).toAbsolutePath().normalize();
        if (!dir.startsWith(workRoot) || dir.equals(workRoot)) {
            log.warn("拒绝删除非任务目录: {}", dir);
            return 0;
        }
        if (!Files.isDirectory(dir)) {
            log.info("任务目录不存在，跳过文件清理: {}", dir);
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
                    log.warn("删除文件失败: {} — {}", p, e.getMessage());
                }
            }
            log.info("已清理任务目录: path={}, deleted={}", dir, deleted);
        } catch (IOException e) {
            log.warn("遍历任务目录失败: {} — {}", dir, e.getMessage());
        }
        return deleted;
    }

    /**
     * 校验文件存在且可读。
     */
    public Path requireExistingFile(String absolutePath, String label) {
        if (absolutePath == null || absolutePath.isBlank()) {
            throw new BusinessException(404, label + "路径为空");
        }
        Path path = Path.of(absolutePath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new BusinessException(404, label + "不存在: " + path);
        }
        return path;
    }

    /**
     * 从 URL 识别平台：douyin / bilibili / youtube / other。
     */
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

    /**
     * 根据文件名推断 Content-Type。
     */
    public String guessMediaType(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (name.endsWith(".webm")) {
            return "video/webm";
        }
        if (name.endsWith(".mkv")) {
            return "video/x-matroska";
        }
        if (name.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (name.endsWith(".m4a")) {
            return "audio/mp4";
        }
        if (name.endsWith(".wav")) {
            return "audio/wav";
        }
        if (name.endsWith(".json")) {
            return "application/json";
        }
        return "application/octet-stream";
    }
}
