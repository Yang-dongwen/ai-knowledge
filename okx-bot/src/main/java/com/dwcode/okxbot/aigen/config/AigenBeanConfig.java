package com.dwcode.okxbot.aigen.config;

import com.dwcode.okxbot.aigen.adapter.llm.LlmChatScriptPlanAdapter;
import com.dwcode.okxbot.aigen.adapter.llm.MockScriptPlanAdapter;
import com.dwcode.okxbot.aigen.adapter.render.MockRenderAdapter;
import com.dwcode.okxbot.aigen.adapter.render.RemotionHttpRenderAdapter;
import com.dwcode.okxbot.aigen.adapter.render.RemotionProcessManager;
import com.dwcode.okxbot.aigen.adapter.tts.AudioDurationHelper;
import com.dwcode.okxbot.aigen.adapter.tts.AutoTtsProvider;
import com.dwcode.okxbot.aigen.adapter.tts.EdgeTtsProvider;
import com.dwcode.okxbot.aigen.adapter.tts.MockTtsProvider;
import com.dwcode.okxbot.aigen.adapter.tts.WindowsSapiTtsProvider;
import com.dwcode.okxbot.aigen.port.ScriptPlanPort;
import com.dwcode.okxbot.aigen.port.TtsPort;
import com.dwcode.okxbot.aigen.port.VideoRenderPort;
import com.dwcode.okxbot.aigen.service.StoryboardNormalizeService;
import com.dwcode.okxbot.aigen.service.StoryboardValidateService;
import com.dwcode.okxbot.aigen.service.TemplateRegistry;
import com.dwcode.okxbot.video.client.LlmChatClient;
import com.dwcode.okxbot.video.util.ProcessExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按配置装配 Port 实现（mock / real）。
 */
@Slf4j
@Configuration
public class AigenBeanConfig {

    @Bean
    public MockScriptPlanAdapter mockScriptPlanAdapter(StoryboardNormalizeService normalizeService) {
        return new MockScriptPlanAdapter(normalizeService);
    }

    @Bean
    public LlmChatScriptPlanAdapter llmChatScriptPlanAdapter(
            LlmChatClient llmChatClient,
            ObjectMapper objectMapper,
            StoryboardValidateService validateService,
            StoryboardNormalizeService normalizeService,
            TemplateRegistry templateRegistry,
            AigenProperties aigenProperties) {
        return new LlmChatScriptPlanAdapter(
                llmChatClient, objectMapper, validateService, normalizeService, templateRegistry, aigenProperties);
    }

    @Bean
    public ScriptPlanPort scriptPlanPort(AigenProperties props,
                                         MockScriptPlanAdapter mock,
                                         LlmChatScriptPlanAdapter llm) {
        String mode = resolveMode(props, props.getSteps().getPlan());
        log.info("Aigen ScriptPlanPort mode={}", mode);
        return "mock".equalsIgnoreCase(mode) ? mock : llm;
    }

    @Bean
    public MockTtsProvider mockTtsProvider() {
        return new MockTtsProvider();
    }

    @Bean
    public EdgeTtsProvider edgeTtsProvider(AigenProperties props,
                                           ProcessExecutor processExecutor,
                                           AudioDurationHelper durationHelper) {
        return new EdgeTtsProvider(props, processExecutor, durationHelper);
    }

    @Bean
    public WindowsSapiTtsProvider windowsSapiTtsProvider(AigenProperties props,
                                                         ProcessExecutor processExecutor,
                                                         AudioDurationHelper durationHelper) {
        return new WindowsSapiTtsProvider(props, processExecutor, durationHelper);
    }

    @Bean
    public AutoTtsProvider autoTtsProvider(AigenProperties props,
                                           EdgeTtsProvider edge,
                                           WindowsSapiTtsProvider windows,
                                           MockTtsProvider mock) {
        return new AutoTtsProvider(props, edge, windows, mock);
    }

    @Bean
    public TtsPort ttsPort(AigenProperties props,
                           MockTtsProvider mock,
                           AutoTtsProvider auto) {
        String mode = resolveMode(props, props.getSteps().getAsset());
        if ("mock".equalsIgnoreCase(mode)) {
            log.info("Aigen TtsPort mode=mock (steps.asset=mock 或 mock-pipeline)");
            return mock;
        }
        log.info("Aigen TtsPort mode=real, provider={}", props.getTts().getProvider());
        return auto;
    }

    @Bean
    public MockRenderAdapter mockRenderAdapter() {
        return new MockRenderAdapter();
    }

    @Bean
    public RemotionHttpRenderAdapter remotionHttpRenderAdapter(
            AigenProperties props,
            ObjectMapper objectMapper,
            RemotionProcessManager processManager) {
        return new RemotionHttpRenderAdapter(props, objectMapper, processManager);
    }

    @Bean
    public VideoRenderPort videoRenderPort(AigenProperties props,
                                           MockRenderAdapter mock,
                                           RemotionHttpRenderAdapter remotion) {
        String mode = resolveMode(props, props.getSteps().getRender());
        log.info("Aigen VideoRenderPort mode={}", mode);
        return "mock".equalsIgnoreCase(mode) ? mock : remotion;
    }

    private static String resolveMode(AigenProperties props, String stepMode) {
        if (props.isMockPipeline()) {
            return "mock";
        }
        return stepMode != null && !stepMode.isBlank() ? stepMode : "real";
    }
}
