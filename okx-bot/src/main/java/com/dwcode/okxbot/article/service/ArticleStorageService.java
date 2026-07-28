package com.dwcode.okxbot.article.service;

import com.dwcode.okxbot.article.entity.ArticleTaskEntity;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.storage.ObjectKeyBuilder;
import com.dwcode.okxbot.storage.ObjectStoragePort;
import com.dwcode.okxbot.storage.ScratchWorkspace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文章任务 scratch + 对象存储（module=article）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleStorageService {

    public static final String MODULE = "article";

    private final ObjectStoragePort objectStorage;
    private final ObjectKeyBuilder keyBuilder;
    private final ScratchWorkspace scratchWorkspace;

    public Path ensureTaskDir(String taskId) {
        return scratchWorkspace.openTaskScratch(MODULE, taskId);
    }

    public void writeText(Path workDir, String relative, String content) {
        try {
            Path p = workDir.resolve(relative).normalize();
            if (!p.startsWith(workDir.toAbsolutePath().normalize())) {
                throw new BusinessException(400, "非法路径: " + relative);
            }
            Files.createDirectories(p.getParent() != null ? p.getParent() : workDir);
            Files.writeString(p, content != null ? content : "", StandardCharsets.UTF_8);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("写文件失败 {}: {}", relative, e.getMessage());
        }
    }

    /**
     * 成功后将 main.txt / core.json / rewrite.json / result.json 上传对象存储。
     */
    public void persistOutputs(ArticleTaskEntity task, Path workDir) {
        if (task == null || task.getId() == null || task.getUserId() == null) {
            return;
        }
        long userId = task.getUserId();
        String taskId = String.valueOf(task.getId());
        try {
            publishFile(workDir, "main.txt", userId, taskId, task, true);
            publishFile(workDir, "core.json", userId, taskId, null, false);
            publishFile(workDir, "rewrite.json", userId, taskId, null, false);
            publishFile(workDir, "result.json", userId, taskId, null, false);
        } catch (Exception e) {
            log.warn("persist article outputs 失败: {}", e.getMessage());
        }
    }

    private void publishFile(Path workDir, String name, long userId, String taskId,
                             ArticleTaskEntity task, boolean setMainTextPath) {
        Path file = workDir.resolve(name);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            String key = keyBuilder.build(MODULE, userId, taskId, name);
            objectStorage.putBytes(key, bytes, contentType(name));
            if (setMainTextPath && task != null) {
                task.setMainTextPath(key);
            }
            log.debug("已上传 article 对象: {}", key);
        } catch (Exception e) {
            log.warn("上传 {} 失败: {}", name, e.getMessage());
        }
    }

    private static String contentType(String name) {
        if (name.endsWith(".json")) {
            return "application/json";
        }
        return "text/plain; charset=utf-8";
    }

    public void deleteTaskStorage(long userId, String taskId) {
        try {
            scratchWorkspace.cleanupScratch(MODULE, taskId);
        } catch (Exception e) {
            log.warn("清理 scratch 失败: {}", e.getMessage());
        }
        try {
            String prefix = keyBuilder.taskPrefix(MODULE, userId, taskId);
            objectStorage.deletePrefix(prefix);
        } catch (Exception e) {
            log.warn("清理对象存储失败: {}", e.getMessage());
        }
    }

    /**
     * 优先读全文（scratch main.txt / 对象存储），再回落 DB 截断副本。
     */
    public String readMainTextFromPath(ArticleTaskEntity task) {
        if (task == null) {
            return null;
        }
        // 1) 流水线 scratch
        if (task.getWorkDir() != null && !task.getWorkDir().isBlank()) {
            try {
                Path local = Path.of(task.getWorkDir()).resolve("main.txt");
                if (Files.isRegularFile(local)) {
                    String text = Files.readString(local, StandardCharsets.UTF_8);
                    if (text != null && !text.isBlank()) {
                        return text;
                    }
                }
            } catch (Exception e) {
                log.debug("读 scratch main.txt 失败: {}", e.getMessage());
            }
        }
        // 2) 对象存储 / 本地绝对路径
        String pathOrKey = task.getMainTextPath();
        if (pathOrKey != null && !pathOrKey.isBlank()) {
            try {
                if (ObjectKeyBuilder.looksLikeLocalAbsolutePath(pathOrKey)) {
                    String text = Files.readString(Path.of(pathOrKey), StandardCharsets.UTF_8);
                    if (text != null && !text.isBlank()) {
                        return text;
                    }
                } else {
                    try (InputStream in = objectStorage.openStream(pathOrKey)) {
                        String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        if (text != null && !text.isBlank()) {
                            return text;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("读取 main text 存储失败: {}", e.getMessage());
            }
        }
        // 3) DB 截断副本
        return task.getMainText();
    }
}
