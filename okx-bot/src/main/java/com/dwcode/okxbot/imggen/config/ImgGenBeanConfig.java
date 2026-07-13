package com.dwcode.okxbot.imggen.config;

import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.imggen.adapter.CompositeImageGenPort;
import com.dwcode.okxbot.imggen.adapter.flux.NvidiaFluxImageAdapter;
import com.dwcode.okxbot.imggen.adapter.llm.LlmPromptEnhanceAdapter;
import com.dwcode.okxbot.imggen.adapter.mock.MockImageGenAdapter;
import com.dwcode.okxbot.imggen.adapter.qwen.NvidiaQwenImageAdapter;
import com.dwcode.okxbot.imggen.port.ImageGenPort;
import com.dwcode.okxbot.imggen.port.PromptEnhancePort;
import com.dwcode.okxbot.video.client.LlmChatClient;
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
    public MockImageGenAdapter mockImageGenAdapter(ImgGenProperties props) {
        return new MockImageGenAdapter(props);
    }

    @Bean
    public NvidiaFluxImageAdapter nvidiaFluxImageAdapter(ImgGenProperties props,
                                                         AiProperties aiProperties,
                                                         ObjectMapper objectMapper) {
        return new NvidiaFluxImageAdapter(props, aiProperties, objectMapper);
    }

    @Bean
    public NvidiaQwenImageAdapter nvidiaQwenImageAdapter(ImgGenProperties props,
                                                         AiProperties aiProperties,
                                                         ObjectMapper objectMapper) {
        return new NvidiaQwenImageAdapter(props, aiProperties, objectMapper);
    }

    @Bean
    public ImageGenPort imageGenPort(ImgGenProperties props,
                                     MockImageGenAdapter mock,
                                     NvidiaFluxImageAdapter flux,
                                     NvidiaQwenImageAdapter qwen) {
        if (props.isMockPipeline() || "mock".equalsIgnoreCase(props.getSteps().getGenerate())) {
            log.info("ImgGen ImageGenPort mode=mock");
            return mock;
        }
        log.info("ImgGen ImageGenPort mode=composite (flux + qwen + openai-images)");
        return new CompositeImageGenPort(flux, qwen, mock);
    }
}
