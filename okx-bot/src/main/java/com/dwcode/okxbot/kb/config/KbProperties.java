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
    }

    @Data
    public static class Category {
        /** 树最大深度（根为 1） */
        private int maxDepth = 3;
    }

    @Data
    public static class Search {
        /** like | fulltext（fulltext 需 ngram 索引；失败会降级 like） */
        private String mode = "like";
    }

    @Data
    public static class File {
        private long maxImageBytes = 10L * 1024 * 1024;
        private long maxVideoBytes = 100L * 1024 * 1024;
        private long maxOtherBytes = 30L * 1024 * 1024;
    }
}
