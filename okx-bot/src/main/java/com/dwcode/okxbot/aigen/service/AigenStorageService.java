package com.dwcode.okxbot.aigen.service;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.entity.AigenTaskEntity;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.storage.LocalObjectStorage;
import com.dwcode.okxbot.storage.ObjectKeyBuilder;
import com.dwcode.okxbot.storage.ObjectMeta;
import com.dwcode.okxbot.storage.ObjectStoragePort;
import com.dwcode.okxbot.storage.ScratchWorkspace;
import com.dwcode.okxbot.storage.config.StorageProperties;
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
 * aigen 任务目录 + 对象存储持久化（PR4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AigenStorageService {

    public static final String MODULE = "aigen";

    private final AigenProperties aigenProperties;
    private final ObjectStoragePort objectStorage;
    private final ObjectKeyBuilder keyBuilder;
    private final ScratchWorkspace scratchWorkspace;
    private final StorageProperties storageProperties;

    public Path resolveWorkRoot() {
        return scratchWorkspace.resolveRoot().resolve(MODULE).toAbsolutePath().normalize();
    }

    public Path resolveTaskDir(String taskId) {
        return scratchWorkspace.resolveTaskScratch(MODULE, taskId);
    }

    public Path ensureTaskDir(String taskId) throws IOException {
        Path dir = scratchWorkspace.openTaskScratch(MODULE, taskId);
        Files.createDirectories(dir.resolve("assets").resolve("audio"));
        Files.createDirectories(dir.resolve("assets").resolve("images"));
        Files.createDirectories(dir.resolve("assets").resolve("visual"));
        Files.createDirectories(dir.resolve("logs"));
        return dir;
    }

    public Path resolveAsset(Path workDir, String relativeSrc) {
        if (relativeSrc == null || relativeSrc.isBlank()) {
            throw new BusinessException("资源路径为空");
        }
        if (relativeSrc.contains("..") || relativeSrc.startsWith("/") || relativeSrc.contains(":")) {
            throw new BusinessException("非法资源路径: " + relativeSrc);
        }
        if (!relativeSrc.startsWith("assets/")) {
            throw new BusinessException("资源必须位于 assets/ 下: " + relativeSrc);
        }
        Path base = workDir.toAbsolutePath().normalize();
        Path target = base.resolve(relativeSrc).normalize().toAbsolutePath();
        if (!target.startsWith(base)) {
            throw new BusinessException("路径穿越被拒绝: " + relativeSrc);
        }
        Path assetsRoot = base.resolve("assets").normalize();
        if (!target.startsWith(assetsRoot)) {
            throw new BusinessException("路径不在 assets 内: " + relativeSrc);
        }
        return target;
    }

    public void writeRequestSnapshot(Path workDir, String prompt, String templateId) {
        try {
            String body = "templateId=" + templateId + "\n"
                    + "prompt=\n" + (prompt != null ? prompt : "") + "\n";
            Files.writeString(workDir.resolve("request.txt"), body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("写 request 快照失败: {}", e.getMessage());
        }
    }

    /**
     * 成功：上传 output.mp4 + assets/** 等到对象存储，outputPath 改为 key，清 scratch。
     */
    public void persistAndCleanupAfterSuccess(AigenTaskEntity task) {
        if (task == null || task.getId() == null) {
            return;
        }
        long userId = effectiveUserId(task);
        String taskId = String.valueOf(task.getId());
        Path workDir = resolveTaskDir(taskId);
        try {
            // output
            Path outLocal = resolveExistingLocal(task.getOutputPath());
            if (outLocal == null) {
                Path mp4 = workDir.resolve("output.mp4");
                if (Files.isRegularFile(mp4)) {
                    outLocal = mp4;
                }
            }
            if (outLocal != null) {
                task.setOutputPath(publishFile(userId, taskId, outLocal, "output.mp4"));
                try {
                    task.setOutputSizeBytes(Files.size(outLocal));
                } catch (IOException ignored) {
                    // ignore
                }
            }

            // 上传 workDir 下全部 durable 文件（assets、output 等）
            if (Files.isDirectory(workDir)) {
                Path root = workDir.toAbsolutePath().normalize();
                Files.walkFileTree(workDir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        try {
                            if (!Files.isRegularFile(file)) {
                                return FileVisitResult.CONTINUE;
                            }
                            String rel = root.relativize(file.toAbsolutePath().normalize())
                                    .toString().replace('\\', '/');
                            if (rel.isBlank() || rel.contains("..")) {
                                return FileVisitResult.CONTINUE;
                            }
                            // 跳过纯日志超大临时？logs 可上传也可跳过
                            if (rel.startsWith("logs/") && attrs.size() > 5_000_000) {
                                return FileVisitResult.CONTINUE;
                            }
                            publishFile(userId, taskId, file, rel);
                        } catch (Exception e) {
                            log.warn("aigen 上传跳过 {}: {}", file.getFileName(), e.getMessage());
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }

            log.info("aigen 产物已持久化: taskId={}, provider={}, output={}",
                    taskId, objectStorage.providerId(), task.getOutputPath());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("上传 aigen 产物失败: " + e.getMessage());
        } finally {
            if (storageProperties.getCleanup().isScratchOnSuccess()) {
                scratchWorkspace.cleanupScratch(MODULE, taskId);
                deleteLegacyWorkDir(taskId);
            }
        }
    }

    public void cleanupAfterFailure(AigenTaskEntity task) {
        if (task == null || task.getId() == null) {
            return;
        }
        String taskId = String.valueOf(task.getId());
        if (storageProperties.getCleanup().isScratchOnFailure()) {
            scratchWorkspace.cleanupScratch(MODULE, taskId);
            deleteLegacyWorkDir(taskId);
        }
        if (storageProperties.getCleanup().isR2OnFailure()
                && (task.getOutputPath() == null
                || ObjectKeyBuilder.looksLikeLocalAbsolutePath(task.getOutputPath()))) {
            try {
                objectStorage.deletePrefix(keyBuilder.taskPrefix(MODULE, effectiveUserId(task), taskId));
            } catch (Exception e) {
                log.warn("aigen 失败清理对象前缀忽略: {}", e.getMessage());
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
            log.warn("aigen 删除对象前缀失败: {}", e.getMessage());
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
                Path p = Path.of(pathOrKey);
                return Files.isRegularFile(p) && Files.size(p) >= 1024L;
            } catch (Exception e) {
                return false;
            }
        }
        try {
            Optional<ObjectMeta> m = objectStorage.head(pathOrKey);
            return m.isPresent() && m.get().getSizeBytes() >= 1024L;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 解析成片 object key 或本地绝对路径（供 PR5 media-url）。
     */
    public String resolveOutputKeyOrPath(AigenTaskEntity task) {
        String loc = task.getOutputPath();
        if (loc == null || loc.isBlank()) {
            throw new BusinessException(404, "成片尚未生成");
        }
        if (ObjectKeyBuilder.looksLikeLocalAbsolutePath(loc)) {
            Path path = Path.of(loc).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new BusinessException(404, "成片文件不存在");
            }
            return path.toString();
        }
        if (objectStorage.exists(loc)) {
            return loc;
        }
        String key = keyBuilder.build(MODULE, effectiveUserId(task), String.valueOf(task.getId()), "output.mp4");
        if (objectStorage.exists(key)) {
            return key;
        }
        throw new BusinessException(404, "成片对象不存在");
    }

    /**
     * 解析相对资源的 key 或本地绝对路径。
     *
     * @return null 若本地与对象均不存在
     */
    public String resolveRelativeKeyOrPath(AigenTaskEntity task, String relative) {
        if (relative == null || relative.isBlank() || relative.contains("..")) {
            return null;
        }
        String rel = relative.replace('\\', '/');
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        String taskId = String.valueOf(task.getId());
        // 1) scratch
        Path scratchRoot = resolveTaskDir(taskId).toAbsolutePath().normalize();
        Path local = scratchRoot.resolve(rel).normalize();
        if (local.startsWith(scratchRoot) && Files.isRegularFile(local)) {
            return local.toString();
        }
        // 2) 实体 workDir（可能与 scratch 不同）
        if (task.getWorkDir() != null && !task.getWorkDir().isBlank()) {
            try {
                Path wd = Path.of(task.getWorkDir()).toAbsolutePath().normalize();
                Path p = wd.resolve(rel).normalize();
                if (p.startsWith(wd) && Files.isRegularFile(p)) {
                    return p.toString();
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        // 3) 遗留 aigen.work-dir
        Path legacyRoot = Path.of(aigenProperties.getWorkDir(), taskId).toAbsolutePath().normalize();
        Path legacy = legacyRoot.resolve(rel).normalize();
        if (legacy.startsWith(legacyRoot) && Files.isRegularFile(legacy)) {
            return legacy.toString();
        }
        // 4) 对象存储（成功后 scratch 已删，镜头图在此）
        String key = keyBuilder.build(MODULE, effectiveUserId(task), taskId, rel);
        if (objectStorage.exists(key)) {
            return key;
        }
        return null;
    }

    public InputStream openOutputMedia(AigenTaskEntity task) {
        return openOutputMedia(task, 0L, Long.MAX_VALUE - 1);
    }

    /** 成片流，支持字节区间（HTTP Range 边下边播）。 */
    public InputStream openOutputMedia(AigenTaskEntity task, long startInclusive, long endInclusive) {
        String loc = task.getOutputPath();
        if (loc == null || loc.isBlank()) {
            throw new BusinessException(404, "成片尚未生成");
        }
        if (ObjectKeyBuilder.looksLikeLocalAbsolutePath(loc)) {
            Path path = Path.of(loc).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new BusinessException(404, "成片文件不存在");
            }
            try {
                long size = Files.size(path);
                long start = Math.max(0, startInclusive);
                if (start >= size) {
                    throw new BusinessException(416, "Requested Range Not Satisfiable");
                }
                long end = Math.min(endInclusive, size - 1);
                InputStream in = Files.newInputStream(path);
                if (start > 0) {
                    in.skipNBytes(start);
                }
                return new com.dwcode.okxbot.common.web.LimitedInputStream(in, end - start + 1);
            } catch (BusinessException e) {
                throw e;
            } catch (IOException e) {
                throw new BusinessException("打开成片失败: " + e.getMessage());
            }
        }
        String key = loc;
        if (!objectStorage.exists(key)) {
            key = keyBuilder.build(MODULE, effectiveUserId(task), String.valueOf(task.getId()), "output.mp4");
            if (!objectStorage.exists(key)) {
                throw new BusinessException(404, "成片对象不存在");
            }
        }
        if (startInclusive <= 0 && endInclusive >= Long.MAX_VALUE / 2) {
            return objectStorage.openStream(key);
        }
        return objectStorage.openStream(key, startInclusive, endInclusive);
    }

    public Optional<ObjectMeta> headOutput(AigenTaskEntity task) {
        String loc = task.getOutputPath();
        if (loc == null || loc.isBlank()) {
            return Optional.empty();
        }
        if (ObjectKeyBuilder.looksLikeLocalAbsolutePath(loc)) {
            try {
                Path p = Path.of(loc);
                if (!Files.isRegularFile(p)) {
                    return Optional.empty();
                }
                return Optional.of(ObjectMeta.builder()
                        .key(loc)
                        .sizeBytes(Files.size(p))
                        .contentType("video/mp4")
                        .lastModifiedEpochMs(Files.getLastModifiedTime(p).toMillis())
                        .build());
            } catch (IOException e) {
                return Optional.empty();
            }
        }
        return objectStorage.head(loc);
    }

    /**
     * 相对任务目录的资源是否存在（本地 scratch/遗留 work-dir 或对象存储）。
     * <p>成功任务清理 scratch 后镜头图只在 R2，列表必须靠此判断 {@code imageAvailable}。
     */
    public boolean relativeExists(AigenTaskEntity task, String relative) {
        return resolveRelativeKeyOrPath(task, relative) != null;
    }

    /**
     * 打开相对任务目录的资源（镜头图等）：先本地，再对象存储。
     */
    public InputStream openRelative(AigenTaskEntity task, String relative) {
        String keyOrPath = resolveRelativeKeyOrPath(task, relative);
        if (keyOrPath == null) {
            throw new BusinessException(404, "资源不存在: " + relative);
        }
        if (ObjectKeyBuilder.looksLikeLocalAbsolutePath(keyOrPath)) {
            try {
                return Files.newInputStream(Path.of(keyOrPath));
            } catch (IOException e) {
                throw new BusinessException("打开本地资源失败: " + e.getMessage());
            }
        }
        return objectStorage.openStream(keyOrPath);
    }

    /**
     * 将相对资源物化到本地（若仅在对象存储中）。
     */
    public Path materializeRelative(AigenTaskEntity task, String relative) {
        String taskId = String.valueOf(task.getId());
        Path local = resolveTaskDir(taskId).resolve(relative).normalize();
        if (Files.isRegularFile(local)) {
            return local;
        }
        try {
            Path parent = local.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String key = keyBuilder.build(MODULE, effectiveUserId(task), taskId, relative.replace('\\', '/'));
            objectStorage.getToFile(key, local);
            return local;
        } catch (Exception e) {
            throw new BusinessException(404, "无法物化资源: " + relative + " — " + e.getMessage());
        }
    }

    public String publishFile(long userId, String taskId, Path localFile, String relative) {
        String key = keyBuilder.build(MODULE, userId, taskId, relative);
        objectStorage.put(key, localFile, LocalObjectStorage.guessContentType(relative));
        return key;
    }

    private int deleteLegacyWorkDir(String taskId) {
        Path dir = Path.of(aigenProperties.getWorkDir(), taskId).toAbsolutePath().normalize();
        Path root = Path.of(aigenProperties.getWorkDir()).toAbsolutePath().normalize();
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
            log.warn("删除 aigen 遗留目录失败: {}", e.getMessage());
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

    public static long effectiveUserId(AigenTaskEntity task) {
        if (task != null && task.getUserId() != null && task.getUserId() > 0) {
            return task.getUserId();
        }
        return 0L;
    }
}
