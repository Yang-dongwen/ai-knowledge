package com.dwcode.okxbot.imggen.service;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.imggen.config.ImgGenProperties;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class ImgGenStorageService {

    private final ImgGenProperties properties;

    public Path resolveWorkRoot() {
        return Path.of(properties.getWorkDir()).toAbsolutePath().normalize();
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

    public int deleteTaskDir(String taskId) {
        Path dir = resolveTaskDir(taskId);
        if (!Files.isDirectory(dir) || !dir.startsWith(resolveWorkRoot())) {
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
            log.warn("删除 imggen 任务目录失败: {} — {}", dir, e.getMessage());
        }
        return count[0];
    }
}
