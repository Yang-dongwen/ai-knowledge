package com.dwcode.okxbot.kb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库模块配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "kb")
public class KbProperties {

    private final Note note = new Note();
    private final Category category = new Category();
    private final Search search = new Search();
    private final File file = new File();
    private final Pdf pdf = new Pdf();

    @Data
    public static class Note {
        /** 正文最大字符数（html 可更大） */
        private int maxContentChars = 2_097_152;
        /** 列表摘要长度 */
        private int snippetChars = 160;
        /** 默认标题 */
        private String defaultTitle = "未命名笔记";
        /** 新建默认格式：html | markdown */
        private String defaultFormat = "html";
        /** 每笔记保留版本数上限 */
        private int maxRevisions = 50;
        /** 自动保存两次版本快照的最小间隔（分钟） */
        private int revisionMinIntervalMinutes = 5;
    }

    @Data
    public static class Category {
        /** 树最大深度（根为 1） */
        private int maxDepth = 3;
    }

    @Data
    public static class Search {
        /**
         * like：title + content_text LIKE（默认，零运维）
         * fulltext：预留；需 ngram 索引时再切
         */
        private String mode = "like";
        /** 命中片段左右各取字符数 */
        private int highlightRadius = 60;
    }

    @Data
    public static class File {
        /** 分片上传总上限（默认 2GB） */
        private long maxBytes = 2L * 1024 * 1024 * 1024;
        /** 兼容旧版整包 POST 上限，须小于网关/Spring multipart */
        private long maxDirectBytes = 100L * 1024 * 1024;
        /** @deprecated 使用 maxBytes；保留配置项以免旧 yml 绑失败 */
        private long maxImageBytes = 10L * 1024 * 1024;
        private long maxVideoBytes = 100L * 1024 * 1024;
        private long maxOtherBytes = 30L * 1024 * 1024;
        /** 分片大小，须 ≥ 5MB（对齐 S3 非末片） */
        private int chunkSizeBytes = 8 * 1024 * 1024;
        private int maxParts = 512;
        private int sessionTtlHours = 24;
        private int maxSessionsPerUser = 3;
        private int maxConcurrentPartsPerUser = 6;
        private int maxConcurrentPartsGlobal = 16;
        private int maxConcurrentCompleteGlobal = 2;
        private String cleanupCron = "0 */15 * * * ?";
    }

    @Data
    public static class Pdf {
        /** 空则自动探测本机 Edge / Chrome / Chromium */
        private String chromePath = "";
        private int timeoutSeconds = 60;
        /** Docker / Linux 下无头 Chrome 通常需要 */
        private boolean noSandbox = true;
        /** 提交的 HTML 正文上限（含内联图片） */
        private int maxHtmlChars = 16_000_000;
    }
}
