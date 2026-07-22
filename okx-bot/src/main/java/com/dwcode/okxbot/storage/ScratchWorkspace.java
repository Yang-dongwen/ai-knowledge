package com.dwcode.okxbot.storage;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.storage.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 流水线临时工作区（始终本地）。
 * <pre>{scratch.root}/{module}/{taskId}/</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScratchWorkspace {

    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._@+-]+$");

    private final StorageProperties storageProperties;

    public Path resolveRoot() {
        return Path.of(storageProperties.getScratch().getRoot()).toAbsolutePath().normalize();
    }

    /**
     * 确保并返回任务 scratch 目录。
     */
    public Path openTaskScratch(String module, String taskId) {
        Path dir = resolveTaskScratch(module, taskId);
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new BusinessException("无法创建 scratch 目录: " + e.getMessage());
        }
    }

    public Path resolveTaskScratch(String module, String taskId) {
        String mod = requireSafe(module, "module");
        String tid = requireSafe(taskId, "taskId");
        Path root = resolveRoot();
        Path dir = root.resolve(mod).resolve(tid).normalize();
        if (!dir.startsWith(root)) {
            throw new BusinessException("scratch 路径越界");
        }
        return dir;
    }

    /**
     * 删除任务 scratch 目录（不存在则 0）。
     *
     * @return 删除的文件/目录节点数
     */
    public int cleanupScratch(String module, String taskId) {
        Path dir = resolveTaskScratch(module, taskId);
        Path root = resolveRoot();
        if (!dir.startsWith(root) || dir.equals(root)) {
            log.warn("拒绝清理非 scratch 任务目录: {}", dir);
            return 0;
        }
        if (!Files.isDirectory(dir)) {
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
            log.info("已清理 scratch: module={}, taskId={}, nodes={}", module, taskId, count[0]);
        } catch (IOException e) {
            log.warn("清理 scratch 失败: {} — {}", dir, e.getMessage());
        }
        return count[0];
    }

    private static String requireSafe(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(400, label + " 不能为空");
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if ("module".equals(label)) {
            s = raw.trim().toLowerCase(Locale.ROOT);
            if (!s.equals("video") && !s.equals("aigen") && !s.equals("imggen") && !s.equals("article")) {
                throw new BusinessException(400, "非法 module: " + raw);
            }
            return s;
        }
        s = raw.trim();
        if (!SAFE.matcher(s).matches()) {
            throw new BusinessException(400, "非法 " + label + ": " + raw);
        }
        return s;
    }
}
