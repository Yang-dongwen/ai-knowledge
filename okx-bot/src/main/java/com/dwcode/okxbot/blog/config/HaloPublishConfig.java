package com.dwcode.okxbot.blog.config;

import com.dwcode.okxbot.blog.adapter.DisabledHaloPublishAdapter;
import com.dwcode.okxbot.blog.adapter.HaloHttpPublishAdapter;
import com.dwcode.okxbot.blog.port.HaloPublishPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HaloPublishConfig {

    @Bean
    public HaloPublishPort haloPublishPort(HaloProperties properties, ObjectMapper objectMapper) {
        if (!properties.isConfigured()) {
            return new DisabledHaloPublishAdapter();
        }
        return new HaloHttpPublishAdapter(properties, objectMapper);
    }
}
