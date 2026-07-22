package com.dwcode.okxbot.article.util;

import com.dwcode.okxbot.article.enums.ArticleSupportLevel;
import lombok.Builder;
import lombok.Data;

/**
 * 平台识别结果。
 */
@Data
@Builder
public class ArticlePlatformInfo {
    /** generic / zhihu / weibo / x / weixin / xiaohongshu / toutiao / bilibili_column / other */
    private String platform;
    private ArticleSupportLevel supportLevel;
    /** 人类可读说明 */
    private String message;
    private String host;
}
