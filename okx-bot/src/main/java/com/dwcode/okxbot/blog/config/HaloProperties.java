package com.dwcode.okxbot.blog.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Halo 旁挂发文配置。token 为空则视为未启用。
 */
@Data
@Component
@ConfigurationProperties(prefix = "halo")
public class HaloProperties {

    /** 总开关；仍须配置 token */
    private boolean enabled = false;

    /** 内部地址，例如 http://halo:8090 */
    private String baseUrl = "http://127.0.0.1:8090";

    /** 个人令牌 pat_… */
    private String token = "";

    /** 对外站点，拼 permalink */
    private String publicBaseUrl = "https://blog.dwcode.cloud";

    /** 创建/更新后是否立即发布 */
    private boolean publishOnCreate = true;

    /**
     * 用户 PAT 落库加密密钥。空则回退 auth.jwt.secret。
     */
    private String tokenSecret = "";

    public boolean isConfigured() {
        return enabled && StringUtils.hasText(token) && StringUtils.hasText(baseUrl);
    }
}
