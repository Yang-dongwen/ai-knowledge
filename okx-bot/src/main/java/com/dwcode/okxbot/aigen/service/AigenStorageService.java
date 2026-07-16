package com.dwcode.okxbot.aigen.service;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * aigen 任务目录与路径安全解析。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AigenStorageService {

    private final AigenProperties aigenProperties;

    public Path resolveWorkRoot() {
        return Path.of(aigenProperties.getWorkDir()).toAbsolutePath().normalize();
    }

    public Path resolveTaskDir(String taskId) {
        return resolveWorkRoot().resolve(taskId).normalize();
    }

    public Path ensureTaskDir(String taskId) throws IOException {
        Path dir = resolveTaskDir(taskId);
        if (!dir.startsWith(resolveWorkRoot())) {
            throw new BusinessException("非法任务目录");
        }
        Files.createDirectories(dir);
        Files.createDirectories(dir.resolve("assets").resolve("audio"));
        Files.createDirectories(dir.resolve("assets").resolve("images"));
        Files.createDirectories(dir.resolve("assets").resolve("visual"));
        Files.createDirectories(dir.resolve("logs"));
        return dir;
    }

    /**
     * 将相对路径解析到 workDir 下，禁止逃逸；仅允许 assets/ 下资源。
     */
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

    public int deleteTaskDir(String taskId) {
        Path dir = resolveTaskDir(taskId);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        // 二次确认在 work root 下
        if (!dir.startsWith(resolveWorkRoot())) {
            log.warn("拒绝删除非 work-root 目录: {}", dir);
            return 0;
        }
        int[] count = {0};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    count[0]++;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("删除 aigen 任务目录失败: {} — {}", dir, e.getMessage());
        }
        return count[0];
    }
}
