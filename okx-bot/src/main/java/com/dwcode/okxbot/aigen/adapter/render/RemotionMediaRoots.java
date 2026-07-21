package com.dwcode.okxbot.aigen.adapter.render;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.storage.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Remotion 可读媒体根：须同时覆盖
 * <ul>
 *   <li>PR3+ scratch：{@code data/_scratch/aigen/{taskId}}</li>
 *   <li>遗留 work-dir：{@code data/aigen/{taskId}}</li>
 *   <li>BGM 等：{@code data/aigen/_bgm}</li>
 * </ul>
 * 取 scratch 根与 aigen.work-dir 的公共祖先（通常是 {@code data/}）。
 */
@Component
@RequiredArgsConstructor
public class RemotionMediaRoots {

    private final AigenProperties aigenProperties;
    private final StorageProperties storageProperties;

    /**
     * 传给 Node {@code ALLOWED_WORK_ROOT}，且作为 {@code /media/**} 静态根。
     */
    public Path resolveAllowedWorkRoot() {
        Path aigenWd = Path.of(aigenProperties.getWorkDir()).toAbsolutePath().normalize();
        Path scratchRoot = Path.of(storageProperties.getScratch().getRoot()).toAbsolutePath().normalize();
        return commonAncestor(aigenWd, scratchRoot);
    }

    /**
     * 任务 workDir 相对媒体根的 URL 段，例如 {@code _scratch/aigen/2079...} 或 {@code aigen/2079...}。
     */
    public String mediaRelativeTaskPath(Path workDir) {
        if (workDir == null) {
            return "";
        }
        Path wd = workDir.toAbsolutePath().normalize();
        Path root = resolveAllowedWorkRoot();
        try {
            Path rel = root.relativize(wd);
            String s = rel.toString().replace('\\', '/');
            while (s.startsWith("./")) {
                s = s.substring(2);
            }
            if (s.startsWith("..") || s.isBlank()) {
                // 不在根下：退回仅 taskId（旧行为）
                return wd.getFileName() != null ? wd.getFileName().toString() : "";
            }
            return s;
        } catch (Exception e) {
            return wd.getFileName() != null ? wd.getFileName().toString() : "";
        }
    }

    static Path commonAncestor(Path a, Path b) {
        Path pa = a.toAbsolutePath().normalize();
        Path pb = b.toAbsolutePath().normalize();
        // 逐级上溯较短路径
        while (pa != null && !pb.startsWith(pa)) {
            pa = pa.getParent();
        }
        if (pa == null) {
            // 极端：不同盘符，用 a 的父
            Path parent = a.toAbsolutePath().normalize().getParent();
            return parent != null ? parent : a.toAbsolutePath().normalize();
        }
        return pa;
    }
}
