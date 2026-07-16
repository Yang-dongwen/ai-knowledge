package com.dwcode.okxbot.common.ai;

import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.chat.config.AiProperties.ProviderConfig;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.dto.LlmModelTestResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 全局 OpenAI 兼容 LLM 聊天客户端（三工具统一入口）。
 * <p>
 * 包路径：{@code com.dwcode.okxbot.common.ai}（跨 video / aigen / imggen，不属于 video 模块私有）。
 * <p>
 * Phase A：默认委托 {@link LlmChatGateway}（LangChain4j）；
 * 配置 {@code ai.chat-engine=okhttp} 时走本类内嵌的 OkHttp 实现以便回滚。
 * <p>
 * 默认温度 / 重试等仍读 {@code video.llm.*}（历史配置前缀，后续可抽到 {@code ai.llm}）。
 */
@Slf4j
@Component
public class LlmChatClient {

    private final AiProperties aiProperties;
    private final VideoProperties videoProperties;
    private final ObjectMapper objectMapper;
    private final LlmChatGateway llmChatGateway;
    private final OkHttpClient httpClient;
    /** 模型测试专用短超时客户端 */
    private final OkHttpClient testHttpClient;

    public LlmChatClient(AiProperties aiProperties,
                         VideoProperties videoProperties,
                         ObjectMapper objectMapper,
                         LlmChatGateway llmChatGateway) {
        this.aiProperties = aiProperties;
        this.videoProperties = videoProperties;
        this.objectMapper = objectMapper;
        this.llmChatGateway = llmChatGateway;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        int testSec = Math.max(1, videoProperties.getLlm().getTestTimeoutSeconds());
        this.testHttpClient = httpClient.newBuilder()
                .connectTimeout(Math.min(10, testSec), TimeUnit.SECONDS)
                .readTimeout(testSec, TimeUnit.SECONDS)
                .writeTimeout(Math.min(10, testSec), TimeUnit.SECONDS)
                .callTimeout(testSec, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 使用 video.llm 默认供应商/模型。
     */
    public String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, null, null);
    }

    /**
     * 发送 chat completion。
     *
     * @param providerKey 可空，空则用 video.llm.provider / 默认
     * @param modelId     可空，空则用 video.llm.model / 供应商第一个模型
     */
    public String chat(String systemPrompt, String userPrompt, String providerKey, String modelId) {
        return chat(systemPrompt, userPrompt, providerKey, modelId, null);
    }

    /**
     * 发送 chat completion（可覆盖温度 / JSON 模式等，Phase B 结构化场景用）。
     */
    public String chat(String systemPrompt,
                       String userPrompt,
                       String providerKey,
                       String modelId,
                       LlmCallOptions options) {
        if (llmChatGateway.useLangChain4j()) {
            if (options == null) {
                return llmChatGateway.chat(systemPrompt, userPrompt, providerKey, modelId);
            }
            return llmChatGateway.chat(systemPrompt, userPrompt, providerKey, modelId, options);
        }
        int maxRetries = options != null && options.getMaxRetries() != null
                ? options.getMaxRetries()
                : videoProperties.getLlm().getMaxRetries();
        int maxTokens = options != null && options.getMaxTokens() != null
                ? options.getMaxTokens()
                : videoProperties.getLlm().getMaxTokens();
        double temperature = options != null && options.getTemperature() != null
                ? options.getTemperature()
                : videoProperties.getLlm().getTemperature();
        String responseFormat = options != null ? options.getResponseFormat() : null;
        OkHttpClient client = httpClient;
        if (options != null && options.getTimeoutSeconds() != null) {
            int sec = Math.max(1, options.getTimeoutSeconds());
            client = httpClient.newBuilder()
                    .connectTimeout(Math.min(30, sec), TimeUnit.SECONDS)
                    .readTimeout(sec, TimeUnit.SECONDS)
                    .writeTimeout(Math.min(60, sec), TimeUnit.SECONDS)
                    .callTimeout(sec + 5L, TimeUnit.SECONDS)
                    .build();
        }
        return chatInternal(systemPrompt, userPrompt, providerKey, modelId,
                maxRetries, maxTokens, temperature, responseFormat, client);
    }

    /**
     * 连通性测试：短提示 + 不重试 + 短超时（默认 10s），超时/失败均判不可用。
     */
    public LlmModelTestResponse testModel(String providerKey, String modelId) {
        if (llmChatGateway.useLangChain4j()) {
            return llmChatGateway.testModel(providerKey, modelId);
        }
        long start = System.currentTimeMillis();
        int timeoutSec = Math.max(1, videoProperties.getLlm().getTestTimeoutSeconds());
        try {
            String reply = chatInternal(
                    "你是一个简洁的助手。",
                    "请只回复：OK",
                    providerKey,
                    modelId,
                    0,
                    32,
                    0.1,
                    null,
                    testHttpClient
            );
            return LlmModelTestResponse.builder()
                    .available(true)
                    .provider(providerKey)
                    .model(modelId)
                    .reply(truncate(reply, 200))
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            String msg = e.getMessage() != null ? e.getMessage() : "unknown";
            if (isTimeoutError(msg) || cost >= timeoutSec * 1000L - 50) {
                msg = "超过 " + timeoutSec + " 秒无响应，判定不可用";
            }
            log.warn("模型测试失败(okhttp): provider={}, model={}, {}ms, err={}", providerKey, modelId, cost, msg);
            return LlmModelTestResponse.builder()
                    .available(false)
                    .provider(providerKey)
                    .model(modelId)
                    .latencyMs(cost)
                    .errorMessage(truncate(msg, 500))
                    .build();
        }
    }

    private static boolean isTimeoutError(String msg) {
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("timeout")
                || lower.contains("timed out")
                || lower.contains("deadline")
                || lower.contains("超时");
    }

    private String chatInternal(String systemPrompt,
                                String userPrompt,
                                String providerKey,
                                String modelId,
                                int maxRetries,
                                int maxTokens,
                                double temperature,
                                String responseFormat,
                                OkHttpClient client) {
        ProviderConfig provider = resolveProvider(providerKey);
        String resolvedModel = resolveModelId(provider, modelId);

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt != null ? systemPrompt : ""),
                Map.of("role", "user", "content", userPrompt != null ? userPrompt : "")
        );

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", resolvedModel);
        requestBody.put("messages", messages);
        requestBody.put("temperature", temperature);
        requestBody.put("max_tokens", maxTokens);
        if (responseFormat != null && !responseFormat.isBlank()) {
            requestBody.put("response_format", Map.of("type", responseFormat.trim()));
        }

        final String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(requestBody);
        } catch (IOException e) {
            throw new BusinessException("构造 LLM 请求失败: " + e.getMessage());
        }

        String chatUrl = buildChatUrl(provider.getBaseUrl());
        log.info("调用 LLM(okhttp): provider={}, model={}, url={}", provider.getName(), resolvedModel, chatUrl);

        maxRetries = Math.max(0, maxRetries);
        long backoffMs = Math.max(200L, videoProperties.getLlm().getRetryBackoffMs());
        long maxBackoffMs = Math.max(backoffMs, videoProperties.getLlm().getRetryMaxBackoffMs());

        BusinessException lastBiz = null;
        IOException lastIo = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                long sleepMs = Math.min(maxBackoffMs, backoffMs * (1L << (attempt - 1)));
                sleepMs = sleepMs + (long) (Math.random() * Math.min(1000, sleepMs / 5));
                log.warn("LLM 瞬时失败，第 {}/{} 次重试，等待 {}ms …", attempt, maxRetries, sleepMs);
                sleepQuietly(sleepMs);
            }

            Request httpRequest = new Request.Builder()
                    .url(chatUrl)
                    .addHeader("Authorization", "Bearer " + provider.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                    .build();

            try (Response response = client.newCall(httpRequest).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                int code = response.code();

                if (response.isSuccessful()) {
                    return parseContent(respBody);
                }

                boolean retryable = isRetryableStatus(code) || isRetryableBody(respBody);
                log.error("LLM 请求失败: status={}, attempt={}/{}, retryable={}, body={}",
                        code, attempt, maxRetries, retryable, truncate(respBody, 1000));

                lastBiz = new BusinessException("LLM 服务失败（HTTP " + code + "）: " + truncate(respBody, 300));
                if (!retryable || attempt >= maxRetries) {
                    throw lastBiz;
                }
            } catch (BusinessException e) {
                throw e;
            } catch (IOException e) {
                lastIo = e;
                log.error("LLM 网络异常: attempt={}/{}, err={}", attempt, maxRetries, e.getMessage());
                if (attempt >= maxRetries) {
                    throw new BusinessException("LLM 调用异常（已重试 " + maxRetries + " 次）: " + e.getMessage());
                }
            }
        }

        if (lastBiz != null) {
            throw lastBiz;
        }
        throw new BusinessException("LLM 调用异常: " + (lastIo != null ? lastIo.getMessage() : "unknown"));
    }

    private String parseContent(String respBody) throws IOException {
        JsonNode respJson = objectMapper.readTree(respBody);
        JsonNode choices = respJson.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            String content = choices.get(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                content = choices.get(0).path("message").path("reasoning_content").asText("");
            }
            if (content.isBlank()) {
                throw new BusinessException("LLM 返回空内容");
            }
            return content;
        }
        throw new BusinessException("LLM 未返回有效 choices");
    }

    private static boolean isRetryableStatus(int code) {
        return code == 408 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504;
    }

    private static boolean isRetryableBody(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("resourceexhausted")
                || lower.contains("all workers are busy")
                || lower.contains("please retry")
                || lower.contains("rate limit")
                || lower.contains("too many requests")
                || lower.contains("temporarily unavailable");
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("LLM 重试等待被中断");
        }
    }

    private ProviderConfig resolveProvider(String providerKey) {
        String key = providerKey;
        if (key == null || key.isBlank()) {
            key = videoProperties.getLlm().getProvider();
        }
        if (key != null && !key.isBlank()) {
            ProviderConfig config = aiProperties.getProvider(key);
            if (config != null && config.getApiKey() != null && !config.getApiKey().isEmpty()) {
                return config;
            }
            log.warn("provider={} 不可用，回退默认供应商", key);
        }
        ProviderConfig defaultProvider = aiProperties.getDefaultProvider();
        if (defaultProvider == null || defaultProvider.getApiKey() == null || defaultProvider.getApiKey().isEmpty()) {
            throw new BusinessException("未配置可用的 AI 供应商，请在 application.yml 的 ai.providers 中配置 api-key");
        }
        return defaultProvider;
    }

    private String resolveModelId(ProviderConfig provider, String modelId) {
        if (modelId != null && !modelId.isBlank()) {
            return modelId;
        }
        String cfgModel = videoProperties.getLlm().getModel();
        if (cfgModel != null && !cfgModel.isBlank()) {
            return cfgModel;
        }
        if (provider.getModels() != null && !provider.getModels().isEmpty()) {
            return provider.getModels().get(0).getId();
        }
        throw new BusinessException("未配置 LLM 模型");
    }

    private String buildChatUrl(String baseUrl) {
        String apiUrl = baseUrl;
        if (!apiUrl.endsWith("/")) {
            apiUrl += "/";
        }
        if (apiUrl.endsWith("/v1/")) {
            return apiUrl + "chat/completions";
        } else if (apiUrl.contains("/v1")) {
            return apiUrl + (apiUrl.endsWith("/") ? "" : "/") + "chat/completions";
        } else {
            return apiUrl + "v1/chat/completions";
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
