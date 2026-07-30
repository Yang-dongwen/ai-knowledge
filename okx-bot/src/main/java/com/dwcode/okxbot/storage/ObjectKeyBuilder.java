package com.dwcode.okxbot.storage;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.storage.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 生成规范对象 Key：
 * <pre>{env}/{module}/{userId}/{taskId}/{relativePath}</pre>
 * 例如：{@code prod/video/1001/2079.../video.browser.mp4}
 */
@Component
@RequiredArgsConstructor
public class ObjectKeyBuilder {

    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[A-Za-z0-9._@+-]+$");
    private static final Pattern SAFE_RELATIVE = Pattern.compile("^[A-Za-z0-9._@+/-]+$");

    private final StorageProperties storageProperties;

    /**
     * @param module       video | aigen | imggen | article
     * @param userId       任务所属用户
     * @param taskId       任务 ID（字符串，雪花也行）
     * @param relativePath 任务内相对路径，如 {@code video.browser.mp4} 或 {@code assets/images/a.jpg}
     */
    public String build(String module, long userId, String taskId, String relativePath) {
        String env = normalizeEnv(storageProperties.getEnvPrefix());
        String mod = normalizeModule(module);
        String tid = normalizeTaskId(taskId);
        String rel = normalizeRelative(relativePath);
        if (userId < 0) {
            throw new BusinessException(400, "object key userId 不能为负");
        }
        return env + "/" + mod + "/" + userId + "/" + tid + "/" + rel;
    }

    /**
     * 任务级前缀（末尾带 /），用于 deletePrefix。
     */
    public String taskPrefix(String module, long userId, String taskId) {
        String env = normalizeEnv(storageProperties.getEnvPrefix());
        String mod = normalizeModule(module);
        String tid = normalizeTaskId(taskId);
        if (userId < 0) {
            throw new BusinessException(400, "object key userId 不能为负");
        }
        return env + "/" + mod + "/" + userId + "/" + tid + "/";
    }

    /**
     * 判断字符串是否像「旧版本地绝对路径」（盘符或根路径），便于读兼容。
     */
    public static boolean looksLikeLocalAbsolutePath(String pathOrKey) {
        if (pathOrKey == null || pathOrKey.isBlank()) {
            return false;
        }
        String p = pathOrKey.trim();
        if (p.length() >= 2 && p.charAt(1) == ':') {
            // Windows D:\...
            return true;
        }
        if (p.startsWith("/") || p.startsWith("\\")) {
            return true;
        }
        return p.contains("\\") && (p.contains(":\\") || p.startsWith("\\\\"));
    }

    private static String normalizeEnv(String raw) {
        String e = (raw == null || raw.isBlank()) ? "dev" : raw.trim().toLowerCase(Locale.ROOT);
        e = e.replaceAll("^/+", "").replaceAll("/+$", "");
        if (!SAFE_SEGMENT.matcher(e).matches()) {
            throw new BusinessException(400, "非法 storage.env-prefix: " + raw);
        }
        return e;
    }

    private static String normalizeModule(String module) {
        if (module == null || module.isBlank()) {
            throw new BusinessException(400, "module 不能为空");
        }
        String m = module.trim().toLowerCase(Locale.ROOT);
        return switch (m) {
            case "video", "aigen", "imggen", "article", "kb" -> m;
            default -> throw new BusinessException(400, "不支持的 storage module: " + module);
        };
    }

    private static String normalizeTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new BusinessException(400, "taskId 不能为空");
        }
        String t = taskId.trim();
        if (!SAFE_SEGMENT.matcher(t).matches()) {
            throw new BusinessException(400, "非法 taskId: " + taskId);
        }
        return t;
    }

    private static String normalizeRelative(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new BusinessException(400, "relativePath 不能为空");
        }
        String rel = relativePath.trim().replace('\\', '/');
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        if (rel.isEmpty() || rel.contains("..") || rel.startsWith("/") || rel.contains(":")) {
            throw new BusinessException(400, "非法 relativePath: " + relativePath);
        }
        // 折叠重复斜杠
        rel = rel.replaceAll("/+", "/");
        if (!SAFE_RELATIVE.matcher(rel).matches()) {
            throw new BusinessException(400, "relativePath 含非法字符: " + relativePath);
        }
        return rel;
    }
}
