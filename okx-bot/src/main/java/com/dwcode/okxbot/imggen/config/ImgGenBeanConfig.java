package com.dwcode.okxbot.imggen.config;

import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.imggen.adapter.flux.NvidiaFluxImageAdapter;
import com.dwcode.okxbot.imggen.adapter.llm.LlmPromptEnhanceAdapter;
import com.dwcode.okxbot.imggen.adapter.mock.MockImageGenAdapter;
import com.dwcode.okxbot.imggen.port.ImageGenPort;
import com.dwcode.okxbot.imggen.port.PromptEnhancePort;
import com.dwcode.okxbot.common.ai.LlmChatClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class ImgGenBeanConfig {

    @Bean
    public PromptEnhancePort promptEnhancePort(LlmChatClient llmChatClient) {
        return new LlmPromptEnhanceAdapter(llmChatClient);
    }

    @Bean
    public ImageGenPort imageGenPort(ImgGenProperties props,
                                     AiProperties aiProperties,
                                     ObjectMapper objectMapper) {
        boolean mock = props.isMockPipeline()
                || "mock".equalsIgnoreCase(props.getSteps().getGenerate());
        if (mock) {
            log.info("ImgGen ImageGenPort mode=mock");
            return new MockImageGenAdapter(props);
        }
        log.info("ImgGen ImageGenPort mode=nvidia-flux url={}", props.getFlux().getInvokeUrl());
        return new NvidiaFluxImageAdapter(props, aiProperties, objectMapper);
    }
}
