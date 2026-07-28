package com.dwcode.okxbot.article.port;

import lombok.Builder;
import lombok.Data;

/**
 * 抓取命令：仅保留 Adapter 实际使用的字段。
 * 超时/体积上限由 {@code article.fetch.*} 配置统一控制。
 */
@Data
@Builder
public class ArticleFetchCommand {
    private String url;
    private String platform;
    private String supportLevel;
}
