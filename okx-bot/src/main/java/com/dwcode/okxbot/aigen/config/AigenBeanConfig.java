package com.dwcode.okxbot.aigen.config;

import com.dwcode.okxbot.aigen.adapter.llm.LangChain4jDirectorAdapter;
import com.dwcode.okxbot.aigen.adapter.llm.LangChain4jScriptPlanAdapter;
import com.dwcode.okxbot.aigen.adapter.llm.LlmChatScriptPlanAdapter;
import com.dwcode.okxbot.aigen.adapter.llm.MockDirectorAdapter;
import com.dwcode.okxbot.aigen.adapter.llm.MockScriptPlanAdapter;
import com.dwcode.okxbot.aigen.adapter.render.MockRenderAdapter;
import com.dwcode.okxbot.aigen.adapter.render.RemotionHttpRenderAdapter;
import com.dwcode.okxbot.aigen.adapter.render.RemotionProcessManager;
import com.dwcode.okxbot.aigen.adapter.tts.AudioDurationHelper;
import com.dwcode.okxbot.aigen.adapter.tts.AutoTtsProvider;
import com.dwcode.okxbot.aigen.adapter.tts.EdgeTtsProvider;
import com.dwcode.okxbot.aigen.adapter.tts.MockTtsProvider;
import com.dwcode.okxbot.aigen.adapter.tts.WindowsSapiTtsProvider;
import com.dwcode.okxbot.aigen.port.DirectorPort;
import com.dwcode.okxbot.aigen.port.ScriptPlanPort;
import com.dwcode.okxbot.aigen.port.TtsPort;
import com.dwcode.okxbot.aigen.port.VideoRenderPort;
import com.dwcode.okxbot.aigen.service.ShotlistNormalizeService;
import com.dwcode.okxbot.aigen.service.ShotlistValidateService;
import com.dwcode.okxbot.aigen.service.StoryboardNormalizeService;
import com.dwcode.okxbot.aigen.service.StoryboardValidateService;
import com.dwcode.okxbot.aigen.service.TemplateRegistry;
import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.common.ai.ChatModelFactory;
import com.dwcode.okxbot.common.ai.LlmChatClient;
import com.dwcode.okxbot.video.util.ProcessExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按配置装配 Port 实现（mock / real；plan 在 real 下按 ai.chat-engine 选 Adapter）。
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
    public LangChain4jScriptPlanAdapter langChain4jScriptPlanAdapter(
            ChatModelFactory chatModelFactory,
            LlmChatClient llmChatClient,
            ObjectMapper objectMapper,
            StoryboardValidateService validateService,
            StoryboardNormalizeService normalizeService,
            TemplateRegistry templateRegistry,
            AigenProperties aigenProperties) {
        return new LangChain4jScriptPlanAdapter(
                chatModelFactory, llmChatClient, objectMapper,
                validateService, normalizeService, templateRegistry, aigenProperties);
    }

    @Bean
    public ScriptPlanPort scriptPlanPort(AigenProperties props,
                                         AiProperties aiProperties,
                                         MockScriptPlanAdapter mock,
                                         LlmChatScriptPlanAdapter llmChat,
                                         LangChain4jScriptPlanAdapter langChain4j) {
        String mode = resolveMode(props, props.getSteps().getPlan());
        if ("mock".equalsIgnoreCase(mode)) {
            log.info("Aigen ScriptPlanPort mode=mock");
            return mock;
        }
        if (aiProperties.isLangChain4jChatEngine()) {
            log.info("Aigen ScriptPlanPort mode=real, adapter=LangChain4jScriptPlanAdapter, structuredMode={}",
                    props.getLlm().getStructuredMode());
            return langChain4j;
        }
        log.info("Aigen ScriptPlanPort mode=real, adapter=LlmChatScriptPlanAdapter (okhttp engine)");
        return llmChat;
    }

    @Bean
    public MockDirectorAdapter mockDirectorAdapter(ShotlistNormalizeService normalizeService) {
        return new MockDirectorAdapter(normalizeService);
    }

    @Bean
    public LangChain4jDirectorAdapter langChain4jDirectorAdapter(
            ChatModelFactory chatModelFactory,
            LlmChatClient llmChatClient,
            ObjectMapper objectMapper,
            ShotlistValidateService validateService,
            ShotlistNormalizeService normalizeService,
            AigenProperties aigenProperties) {
        return new LangChain4jDirectorAdapter(
                chatModelFactory, llmChatClient, objectMapper,
                validateService, normalizeService, aigenProperties);
    }

    @Bean
    public DirectorPort directorPort(AigenProperties props,
                                     AiProperties aiProperties,
                                     MockDirectorAdapter mock,
                                     LangChain4jDirectorAdapter langChain4j) {
        String mode = resolveMode(props, props.getSteps().getPlan());
        if ("mock".equalsIgnoreCase(mode)) {
            log.info("Aigen DirectorPort mode=mock");
            return mock;
        }
        // real：优先 LangChain4j 导演（与 chat-engine 一致；okhttp 时仍可用 Gateway 回退）
        log.info("Aigen DirectorPort mode=real, adapter=LangChain4jDirectorAdapter, chatEngine={}",
                aiProperties.isLangChain4jChatEngine() ? "langchain4j" : "okhttp");
        return langChain4j;
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
