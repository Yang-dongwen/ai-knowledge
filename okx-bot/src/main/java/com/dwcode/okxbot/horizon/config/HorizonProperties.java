package com.dwcode.okxbot.horizon.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Horizon 只需两处环境差异：本机开小时刷新、要不要同步 Halo。
 */
@Data
@Component
@ConfigurationProperties(prefix = "horizon")
public class HorizonProperties {

    /** 本机 true，云上 false。关了仍可读已有日报。 */
    private boolean refreshEnabled = false;

    /** 刷新/入库后是否更新 Halo 同一篇 */
    private boolean autoPublish = false;

    /**
     * 可选。只给「Horizon 自己 webhook 打进来」用；空则拒绝 POST /digest。
     * 一般不用配，小时任务不走 webhook。
     */
    private String token = "";

    /** 公开 RSS / 日报条目回链到工具台今日资讯 */
    private String newsPublicUrl = "https://dwcode.cloud/news";

    public boolean webhookOpen() {
        return StringUtils.hasText(token);
    }

    public String newsPublicUrl() {
        return StringUtils.hasText(newsPublicUrl) ? newsPublicUrl.trim() : "https://dwcode.cloud/news";
    }
}
