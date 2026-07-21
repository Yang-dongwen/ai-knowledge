package com.dwcode.okxbot.imggen.service;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.imggen.config.ImgGenProperties;
import com.dwcode.okxbot.imggen.entity.ImgGenTaskEntity;
import com.dwcode.okxbot.storage.LocalObjectStorage;
import com.dwcode.okxbot.storage.ObjectKeyBuilder;
import com.dwcode.okxbot.storage.ObjectMeta;
import com.dwcode.okxbot.storage.ObjectStoragePort;
import com.dwcode.okxbot.storage.ScratchWorkspace;
import com.dwcode.okxbot.storage.config.StorageProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Optional;

/**
 * 文生图存储（PR4：scratch 生成 + 对象存储持久化）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImgGenStorageService {

    public static final String MODULE = "imggen";

    private final ImgGenProperties properties;
    private final ObjectStoragePort objectStorage;
    private final ObjectKeyBuilder keyBuilder;
    private final ScratchWorkspace scratchWorkspace;
    private final StorageProperties storageProperties;
    private final ObjectMapper objectMapper;

    public Path resolveWorkRoot() {
        // 兼容旧代码：语义上改为 scratch 根
        return scratchWorkspace.resolveRoot().resolve(MODULE).toAbsolutePath().normalize();
    }

    public Path resolveTaskDir(String taskId) {
        return scratchWorkspace.resolveTaskScratch(MODULE, taskId);
    }

    public Path ensureTaskDir(String taskId) throws IOException {
        Path dir = scratchWorkspace.openTaskScratch(MODULE, taskId);
        Files.createDirectories(dir.resolve("outputs"));
        Files.createDirectories(dir.resolve("provider"));
        return dir;
    }

    public Path resolveUnderTask(Path workDir, String relative) {
        if (relative == null || relative.isBlank()
                || relative.contains("..") || relative.startsWith("/") || relative.contains(":")) {
            throw new BusinessException("非法资源路径: " + relative);
        }
        Path base = workDir.toAbsolutePath().normalize();
        Path target = base.resolve(relative).normalize().toAbsolutePath();
        if (!target.startsWith(base)) {
            throw new BusinessException("路径穿越被拒绝: " + relative);
        }
        return target;
    }

    public void writeRequestSnapshot(Path workDir, String prompt, String aspect, int n) {
        try {
            String body = "aspectRatio=" + aspect + "\nn=" + n + "\nprompt=\n"
                    + (prompt != null ? prompt : "") + "\n";
            Files.writeString(workDir.resolve("request.txt"), body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("写 request 快照失败: {}", e.getMessage());
        }
    }

    /**
     * 成功：上传 outputs/* 与 cover，更新 entity 路径为 object key，清理 scratch。
     */
    public void persistAndCleanupAfterSuccess(ImgGenTaskEntity task) {
        if (task == null || task.getId() == null) {
            return;
        }
        long userId = effectiveUserId(task);
        String taskId = String.valueOf(task.getId());
        Path workDir = resolveTaskDir(taskId);
        try {
            Path outputs = workDir.resolve("outputs");
            if (Files.isDirectory(outputs)) {
                try (var stream = Files.list(outputs)) {
                    for (Path p : stream.filter(Files::isRegularFile).toList()) {
                        String rel = "outputs/" + p.getFileName();
                        publishFile(userId, taskId, p, rel);
                    }
                }
            }
            // cover
            Path coverLocal = resolveExistingLocal(task.getCoverPath());
            if (coverLocal != null) {
                String name = coverLocal.getFileName().toString();
                String rel = name.startsWith("outputs/") ? name : "outputs/" + name;
                // 若 cover 已在 outputs 下
                if (coverLocal.startsWith(outputs)) {
                    rel = "outputs/" + coverLocal.getFileName();
                }
                task.setCoverPath(publishFile(userId, taskId, coverLocal, rel));
            } else if (task.getCoverPath() != null
                    && !ObjectKeyBuilder.looksLikeLocalAbsolutePath(task.getCoverPath())) {
                // 已是 key
            } else if (Files.isDirectory(outputs)) {
                // 用第一张 outputs 作 cover key
                try (var stream = Files.list(outputs)) {
                    Optional<Path> first = stream.filter(Files::isRegularFile).findFirst();
                    if (first.isPresent()) {
                        String rel = "outputs/" + first.get().getFileName();
                        task.setCoverPath(keyBuilder.build(MODULE, userId, taskId, rel));
                    }
                }
            }

            // resultJson 中 path 改为 relative key 友好：保持 outputs/xxx，media API 用 fileName
            rewriteResultJsonPaths(task, userId, taskId);

            log.info("imggen 产物已持久化: taskId={}, provider={}, cover={}",
                    taskId, objectStorage.providerId(), task.getCoverPath());
        } catch (Exception e) {
            throw new BusinessException("上传文生图产物失败: " + e.getMessage());
        } finally {
            if (storageProperties.getCleanup().isScratchOnSuccess()) {
                scratchWorkspace.cleanupScratch(MODULE, taskId);
                deleteLegacyWorkDir(taskId);
            }
        }
    }

    private void rewriteResultJsonPaths(ImgGenTaskEntity task, long userId, String taskId) {
        if (task.getResultJson() == null || task.getResultJson().isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(task.getResultJson());
            if (!(root instanceof ObjectNode obj) || !obj.has("images") || !obj.get("images").isArray()) {
                return;
            }
            ArrayNode arr = (ArrayNode) obj.get("images");
            for (int i = 0; i < arr.size(); i++) {
                JsonNode n = arr.get(i);
                if (!(n instanceof ObjectNode im)) {
                    continue;
                }
                String path = im.has("path") ? im.get("path").asText() : null;
                if (path == null || path.isBlank()) {
                    continue;
                }
                String fileName = path.contains("/")
                        ? path.substring(path.lastIndexOf('/') + 1)
                        : path;
                String rel = "outputs/" + fileName;
                // 存相对路径仍给前端 mediaUrl 拼 fileName；object key 用于服务端读取
                im.put("path", rel);
                im.put("objectKey", keyBuilder.build(MODULE, userId, taskId, rel));
            }
            task.setResultJson(objectMapper.writeValueAsString(obj));
        } catch (Exception e) {
            log.debug("rewrite resultJson 跳过: {}", e.getMessage());
        }
    }

    public void cleanupAfterFailure(ImgGenTaskEntity task) {
        if (task == null || task.getId() == null) {
            return;
        }
        String taskId = String.valueOf(task.getId());
        if (storageProperties.getCleanup().isScratchOnFailure()) {
            scratchWorkspace.cleanupScratch(MODULE, taskId);
            deleteLegacyWorkDir(taskId);
        }
        if (storageProperties.getCleanup().isR2OnFailure()
                && (task.getCoverPath() == null
                || ObjectKeyBuilder.looksLikeLocalAbsolutePath(task.getCoverPath()))) {
            try {
                objectStorage.deletePrefix(keyBuilder.taskPrefix(MODULE, effectiveUserId(task), taskId));
            } catch (Exception e) {
                log.warn("imggen 失败清理对象前缀忽略: {}", e.getMessage());
            }
        }
    }

    public int deleteTaskStorage(Long userId, String taskId) {
        int n = scratchWorkspace.cleanupScratch(MODULE, taskId);
        n += deleteLegacyWorkDir(taskId);
        long uid = userId != null && userId > 0 ? userId : 0L;
        try {
            n += objectStorage.deletePrefix(keyBuilder.taskPrefix(MODULE, uid, taskId));
        } catch (Exception e) {
            log.warn("imggen 删除对象前缀失败: {}", e.getMessage());
        }
        return n;
    }

    public int deleteTaskDir(String taskId) {
        return deleteTaskStorage(null, taskId);
    }

    public boolean mediaAvailable(String pathOrKey) {
        if (pathOrKey == null || pathOrKey.isBlank()) {
            return false;
        }
        if (ObjectKeyBuilder.looksLikeLocalAbsolutePath(pathOrKey)) {
            try {
                return Files.isRegularFile(Path.of(pathOrKey)) && Files.size(Path.of(pathOrKey)) > 0;
            } catch (Exception e) {
                return false;
            }
        }
        try {
            return objectStorage.exists(pathOrKey);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 解析输出图片 key 或本地路径（PR5 media-url）。
     */
    public String resolveOutputImageKeyOrPath(ImgGenTaskEntity task, String fileName) {
        if (fileName == null || fileName.isBlank()
                || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            throw new BusinessException(400, "非法文件名");
        }
        String taskId = String.valueOf(task.getId());
        Path local = resolveTaskDir(taskId).resolve("outputs").resolve(fileName).normalize();
        if (Files.isRegularFile(local)) {
            return local.toAbsolutePath().toString();
        }
        Path legacy = Path.of(properties.getWorkDir(), taskId, "outputs", fileName).toAbsolutePath().normalize();
        if (Files.isRegularFile(legacy)) {
            return legacy.toString();
        }
        String key = keyBuilder.build(MODULE, effectiveUserId(task), taskId, "outputs/" + fileName);
        if (!objectStorage.exists(key)) {
            throw new BusinessException(404, "文件不存在");
        }
        return key;
    }

    /**
     * 打开单图：fileName 为 outputs 下文件名；兼容旧本地绝对 cover。
     */
    public InputStream openOutputImage(ImgGenTaskEntity task, String fileName) {
        if (fileName == null || fileName.isBlank()
                || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            throw new BusinessException(400, "非法文件名");
        }
        String taskId = String.valueOf(task.getId());
        // 1) 本地 scratch / 旧 work-dir
        Path local = resolveTaskDir(taskId).resolve("outputs").resolve(fileName).normalize();
        if (Files.isRegularFile(local)) {
            try {
                return Files.newInputStream(local);
            } catch (IOException e) {
                throw new BusinessException("打开本地图片失败: " + e.getMessage());
            }
        }
        Path legacy = Path.of(properties.getWorkDir(), taskId, "outputs", fileName).toAbsolutePath().normalize();
        if (Files.isRegularFile(legacy)) {
            try {
                return Files.newInputStream(legacy);
            } catch (IOException e) {
                throw new BusinessException("打开本地图片失败: " + e.getMessage());
            }
        }
        // 2) 对象存储
        String key = keyBuilder.build(MODULE, effectiveUserId(task), taskId, "outputs/" + fileName);
        if (!objectStorage.exists(key)) {
            throw new BusinessException(404, "文件不存在");
        }
        return objectStorage.openStream(key);
    }

    public Optional<ObjectMeta> headOutputImage(ImgGenTaskEntity task, String fileName) {
        String taskId = String.valueOf(task.getId());
        Path local = resolveTaskDir(taskId).resolve("outputs").resolve(fileName);
        try {
            if (Files.isRegularFile(local)) {
                return Optional.of(ObjectMeta.builder()
                        .key(fileName)
                        .sizeBytes(Files.size(local))
                        .contentType(LocalObjectStorage.guessContentType(fileName))
                        .lastModifiedEpochMs(Files.getLastModifiedTime(local).toMillis())
                        .build());
            }
        } catch (IOException ignored) {
            // fall through
        }
        String key = keyBuilder.build(MODULE, effectiveUserId(task), taskId, "outputs/" + fileName);
        return objectStorage.head(key);
    }

    public String publishFile(long userId, String taskId, Path localFile, String relative) {
        String key = keyBuilder.build(MODULE, userId, taskId, relative);
        objectStorage.put(key, localFile, LocalObjectStorage.guessContentType(relative));
        return key;
    }

    private int deleteLegacyWorkDir(String taskId) {
        Path dir = Path.of(properties.getWorkDir(), taskId).toAbsolutePath().normalize();
        Path root = Path.of(properties.getWorkDir()).toAbsolutePath().normalize();
        if (!dir.startsWith(root) || dir.equals(root) || !Files.isDirectory(dir)) {
            return 0;
        }
        int[] count = {0};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.deleteIfExists(file)) {
                        count[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    if (Files.deleteIfExists(d)) {
                        count[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("删除 imggen 遗留目录失败: {}", e.getMessage());
        }
        return count[0];
    }

    private static Path resolveExistingLocal(String path) {
        if (path == null || path.isBlank() || !ObjectKeyBuilder.looksLikeLocalAbsolutePath(path)) {
            return null;
        }
        try {
            Path p = Path.of(path);
            return Files.isRegularFile(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static long effectiveUserId(ImgGenTaskEntity task) {
        if (task != null && task.getUserId() != null && task.getUserId() > 0) {
            return task.getUserId();
        }
        return 0L;
    }
}
