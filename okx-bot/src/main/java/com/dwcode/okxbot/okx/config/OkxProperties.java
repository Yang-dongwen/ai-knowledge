package com.dwcode.okxbot.okx.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OKX 配置属性。
 */
@Data
@Component
@ConfigurationProperties(prefix = "okx")
public class OkxProperties {

    private String baseUrl = "https://www.okx.com";

    private boolean simulated = true;

    private int timeoutSeconds = 10;

    private String proxyHost = "127.0.0.1";

    private int proxyPort = 7897;
}
