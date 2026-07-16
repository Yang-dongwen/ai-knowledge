package com.dwcode.okxbot.common.ai;

import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.chat.config.AiProperties.ProviderConfig;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.config.VideoProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * 按供应商 / 模型动态构建 LangChain4j {@link ChatModel} / {@link StreamingChatModel}。
 * <p>
 * 不注册全局单例 ChatModel，以支持任务级 provider/model 与测试短超时。
 * 密钥与 base-url 复用 {@link AiProperties}（与 chat / 三工具共用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModelFactory {

    private final AiProperties aiProperties;
    private final VideoProperties videoProperties;

    /**
     * 解析供应商 + 模型并构建 ChatModel。
     *
     * @param providerKey 可空
     * @param modelId     可空
     * @param options     可空
     */
    public ChatModel create(String providerKey, String modelId, LlmCallOptions options) {
        ResolvedLlm target = resolve(providerKey, modelId);
        return build(target.providerKey(), target.provider(), target.modelId(), options);
    }

    /**
     * 构建流式 ChatModel（AI 对话 SSE 用）。
     * <p>
     * {@link LlmCallOptions#getTimeoutSeconds()} 映射为 HTTP connect/read 超时；
     * 对流式读而言即为「两次数据之间」的空闲超时，不是整段回复总时长。
     */
    public StreamingChatModel createStreaming(String providerKey, String modelId, LlmCallOptions options) {
        ResolvedLlm target = resolve(providerKey, modelId);
        return buildStreaming(target.providerKey(), target.provider(), target.modelId(), options);
    }

    /**
     * 解析最终使用的 provider key 与 model id（供日志 / 测试回写）。
     */
    public ResolvedLlm resolve(String providerKey, String modelId) {
        String key = blankToNull(providerKey);
        if (key == null) {
            key = blankToNull(videoProperties.getLlm().getProvider());
        }

        ProviderConfig provider = null;
        String resolvedKey = key;
        if (key != null) {
            provider = aiProperties.getProvider(key);
            if (provider == null || isBlank(provider.getApiKey())) {
                log.warn("provider={} 不可用，回退默认供应商", key);
                provider = null;
                resolvedKey = null;
            }
        }
        if (provider == null) {
            provider = aiProperties.getDefaultProvider();
            resolvedKey = findProviderKey(provider);
        }
        if (provider == null || isBlank(provider.getApiKey())) {
            throw new BusinessException("未配置可用的 AI 供应商，请在 application.yml 的 ai.providers 中配置 api-key");
        }
        if (resolvedKey == null) {
            resolvedKey = "default";
        }

        String resolvedModel = blankToNull(modelId);
        if (resolvedModel == null) {
            resolvedModel = blankToNull(videoProperties.getLlm().getModel());
        }
        if (resolvedModel == null && provider.getModels() != null && !provider.getModels().isEmpty()) {
            resolvedModel = provider.getModels().get(0).getId();
        }
        if (resolvedModel == null || resolvedModel.isBlank()) {
            throw new BusinessException("未配置 LLM 模型");
        }

        return new ResolvedLlm(resolvedKey, provider, resolvedModel);
    }

    private ChatModel build(String providerKey,
                            ProviderConfig provider,
                            String modelId,
                            LlmCallOptions options) {
        LlmCallOptions opts = options != null ? options : LlmCallOptions.builder().build();
        double temperature = opts.getTemperature() != null
                ? opts.getTemperature()
                : videoProperties.getLlm().getTemperature();
        int maxTokens = opts.getMaxTokens() != null
                ? opts.getMaxTokens()
                : videoProperties.getLlm().getMaxTokens();
        int timeoutSec = opts.getTimeoutSeconds() != null
                ? Math.max(1, opts.getTimeoutSeconds())
                : 180;

        String baseUrl = normalizeOpenAiBaseUrl(provider.getBaseUrl());
        String responseFormat = blankToNull(opts.getResponseFormat());
        log.info("构建 ChatModel: engine=langchain4j, provider={}, model={}, baseUrl={}, timeout={}s, responseFormat={}",
                providerKey, modelId, baseUrl, timeoutSec, responseFormat);

        var builder = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(provider.getApiKey())
                .modelName(modelId)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(timeoutSec))
                // 外层 LlmChatGateway 统一做指数退避，避免双重重试
                .maxRetries(0)
                // 兼容 DeepSeek 等 reasoning_content → thinking
                .returnThinking(true);

        if (responseFormat != null) {
            // 如 json_object：强制模型输出 JSON（Phase B 结构化场景）
            builder.responseFormat(responseFormat);
        }
        return builder.build();
    }

    private StreamingChatModel buildStreaming(String providerKey,
                                              ProviderConfig provider,
                                              String modelId,
                                              LlmCallOptions options) {
        LlmCallOptions opts = options != null ? options : LlmCallOptions.builder().build();
        double temperature = opts.getTemperature() != null
                ? opts.getTemperature()
                : videoProperties.getLlm().getTemperature();
        int maxTokens = opts.getMaxTokens() != null
                ? opts.getMaxTokens()
                : videoProperties.getLlm().getMaxTokens();
        int timeoutSec = opts.getTimeoutSeconds() != null
                ? Math.max(1, opts.getTimeoutSeconds())
                : 180;

        String baseUrl = normalizeOpenAiBaseUrl(provider.getBaseUrl());
        String responseFormat = blankToNull(opts.getResponseFormat());
        log.info("构建 StreamingChatModel: engine=langchain4j, provider={}, model={}, baseUrl={}, idleReadTimeout={}s",
                providerKey, modelId, baseUrl, timeoutSec);

        // timeout → connectTimeout + readTimeout；流式场景 readTimeout = 空闲无数据超时
        var builder = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(provider.getApiKey())
                .modelName(modelId)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(timeoutSec))
                .returnThinking(true);

        if (responseFormat != null) {
            builder.responseFormat(responseFormat);
        }
        return builder.build();
    }

    /**
     * OpenAiChatModel 期望 baseUrl 指向 .../v1（不含 chat/completions）。
     */
    static String normalizeOpenAiBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException("AI 供应商 base-url 为空");
        }
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/chat/completions")) {
            url = url.substring(0, url.length() - "/chat/completions".length());
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
        }
        if (!url.endsWith("/v1") && !url.contains("/v1/")) {
            url = url + "/v1";
        }
        return url;
    }

    private String findProviderKey(ProviderConfig target) {
        if (target == null) {
            return null;
        }
        for (Map.Entry<String, ProviderConfig> e : aiProperties.getProviders().entrySet()) {
            if (e.getValue() == target) {
                return e.getKey();
            }
        }
        if (target.getName() != null) {
            for (Map.Entry<String, ProviderConfig> e : aiProperties.getProviders().entrySet()) {
                if (target.getName().equals(e.getValue().getName())) {
                    return e.getKey();
                }
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * 解析后的供应商与模型。
     */
    public record ResolvedLlm(String providerKey, ProviderConfig provider, String modelId) {
    }
}
