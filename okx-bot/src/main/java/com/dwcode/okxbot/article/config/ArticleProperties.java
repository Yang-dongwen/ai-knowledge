package com.dwcode.okxbot.article.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文章/新闻提取模块配置（{@code article.*}）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "article")
public class ArticleProperties {

    private boolean enabled = true;
    private String workDir = "./data/article";
    /** 全局调度槽，对齐 imggen */
    private int maxConcurrentTasks = 2;
    /** 每用户并发上限；超限 429 */
    private int maxConcurrentTasksPerUser = 2;
    private boolean mockPipeline = false;
    /** mock 流水线每步延迟（毫秒） */
    private long mockStepDelayMs = 200;
    private boolean respectRobots = false;
    private boolean cleanupOnDelete = true;

    private Async async = new Async();
    private Fetch fetch = new Fetch();
    private Extract extract = new Extract();
    private Llm llm = new Llm();
    private Rewrite rewrite = new Rewrite();
    private Safety safety = new Safety();
    private Degrade degrade = new Degrade();
    private Platform platform = new Platform();

    @Data
    public static class Async {
        private int corePoolSize = 2;
        private int maxPoolSize = 4;
        private int queueCapacity = 50;
    }

    @Data
    public static class Fetch {
        private int connectTimeoutMs = 10_000;
        private int readTimeoutMs = 20_000;
        private int maxBytes = 2_000_000;
        private int maxRedirects = 5;
        private String userAgent = "okx-bot-article-bot/1.0 (+internal; research)";
        private List<String> enabledAdapters = new ArrayList<>(List.of("generic", "paste", "toutiao"));
        private boolean allowUserCookie = false;
        private List<String> acceptContentTypes = new ArrayList<>(
                List.of("text/html", "application/xhtml+xml", "text/plain"));
    }

    @Data
    public static class Extract {
        private int maxMainTextChars = 80_000;
        private double minQualityScore = 0.35;
    }

    @Data
    public static class Llm {
        private String defaultProvider;
        private String defaultModel;
        private double coreTemperature = 0.3;
        private double rewriteTemperature = 0.8;
        private int digestWindowChars = 8_000;
        private int digestSingleWindowChars = 12_000;
        private boolean jsonObjectEnabled = true;
    }

    @Data
    public static class Rewrite {
        private List<String> defaultVariants = new ArrayList<>(
                List.of("short_video_script", "wechat_article", "x_thread"));
        /** false：REWRITE 失败仍可 SUCCESS（degrade） */
        private boolean required = false;
    }

    @Data
    public static class Safety {
        private boolean blockPrivateIp = true;
        private boolean blockMetadataIp = true;
        private List<String> allowedSchemes = new ArrayList<>(List.of("http", "https"));
        private List<String> hostAllowlist = new ArrayList<>();
        /**
         * 仅单测/本地联调用：允许 loopback（127.0.0.1 / ::1）。
         * <strong>生产必须保持 false</strong>，否则 SSRF 防护被架空。
         */
        private boolean allowLoopback = false;
    }

    @Data
    public static class Degrade {
        private boolean needsPasteOnFetchFail = true;
    }

    @Data
    public static class Platform {
        /**
         * 默认 false：UNSUPPORTED 无 paste → NEEDS_PASTE（不 400）。
         */
        private boolean rejectUnsupportedOnCreate = false;
    }
}
