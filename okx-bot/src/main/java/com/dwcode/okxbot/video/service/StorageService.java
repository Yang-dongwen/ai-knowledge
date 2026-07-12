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
import java.util.Locale;

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
