package com.dwcode.okxbot.storage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 统一对象存储配置（PR1：local；后续 r2）。
 *
 * <pre>
 * storage:
 *   provider: local
 *   env-prefix: dev
 *   local:
 *     root: ./data/_objects
 *   scratch:
 *     root: ./data/_scratch
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    /**
     * local | r2
     * PR1 仅实现 local；r2 在 PR2 接入。
     */
    private String provider = "local";

    /**
     * 对象 Key 顶级环境前缀：dev / prod（单桶分文件夹）。
     */
    private String envPrefix = "dev";

    private Local local = new Local();
    private R2 r2 = new R2();
    private Scratch scratch = new Scratch();
    private Cleanup cleanup = new Cleanup();

    /**
     * 对外读模式：proxy | presign | hybrid（PR1 仅文档占位，业务仍走现有 media API）。
     */
    private String serveMode = "proxy";

    public boolean isR2() {
        return "r2".equalsIgnoreCase(provider != null ? provider.trim() : "");
    }

    public boolean isLocal() {
        return !isR2();
    }

    @Data
    public static class Local {
        /** LocalObjectStorage 根目录；key 相对此根 */
        private String root = "./data/_objects";
    }

    @Data
    public static class R2 {
        private String accountId = "";
        private String bucket = "";
        private String accessKeyId = "";
        private String secretAccessKey = "";
        /** 空则按 accountId 拼默认 endpoint */
        private String endpoint = "";
        private String region = "auto";
        private boolean pathStyle = true;
        private int presignTtlSeconds = 900;
        private String publicBaseUrl = "";
        /**
         * 超过该字节数使用 Multipart Upload（默认 100MB）。
         * 0 表示始终单次 PutObject。
         */
        private long multipartThresholdBytes = 100L * 1024 * 1024;
        /** 分片大小（默认 8MB，需 ≥5MB 符合 S3 规范） */
        private long multipartPartSizeBytes = 8L * 1024 * 1024;
    }

    @Data
    public static class Scratch {
        /** 流水线临时目录（始终本地） */
        private String root = "./data/_scratch";
        /** 残留 scratch 清理参考（小时）；0=不按 TTL 扫 */
        private int ttlHours = 24;
    }

    @Data
    public static class Cleanup {
        private boolean scratchOnSuccess = true;
        private boolean scratchOnFailure = true;
        /** 失败时清理该 task 已上传到 R2 的不完整前缀（PR2+） */
        private boolean r2OnFailure = true;
    }
}
