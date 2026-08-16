package com.dwcode.okxbot.blog.config;

import com.dwcode.okxbot.auth.config.AuthProperties;
import com.dwcode.okxbot.blog.HaloTokenCipher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class HaloPublishConfig {

    @Bean
    public HaloTokenCipher haloTokenCipher(HaloProperties haloProperties, AuthProperties authProperties) {
        String secret = StringUtils.hasText(haloProperties.getTokenSecret())
                ? haloProperties.getTokenSecret()
                : authProperties.getJwt().getSecret();
        return new HaloTokenCipher(secret);
    }
}
