package com.dwcode.okxbot.video.client;

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
 * OpenAI 兼容 LLM 聊天客户端。
 *
 * 复用 {@link AiProperties} 中的多供应商配置，与 chat 模块共用 API Key。
 * 支持按任务指定 provider/model；对 429/503 做指数退避重试。
 */
@Slf4j
@Component
public class LlmChatClient {

    private final AiProperties aiProperties;
    private final VideoProperties videoProperties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    public LlmChatClient(AiProperties aiProperties, VideoProperties videoProperties, ObjectMapper objectMapper) {
        this.aiProperties = aiProperties;
        this.videoProperties = videoProperties;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
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
        return chatInternal(systemPrompt, userPrompt, providerKey, modelId,
                videoProperties.getLlm().getMaxRetries(),
                videoProperties.getLlm().getMaxTokens(),
                videoProperties.getLlm().getTemperature());
    }

    /**
     * 连通性测试：短提示 + 少重试，返回结构化结果。
     */
    public LlmModelTestResponse testModel(String providerKey, String modelId) {
        long start = System.currentTimeMillis();
        try {
            String reply = chatInternal(
                    "你是一个简洁的助手。",
                    "请只回复：OK",
                    providerKey,
                    modelId,
                    1,
                    32,
                    0.1
            );
            return LlmModelTestResponse.builder()
                    .available(true)
                    .provider(providerKey)
                    .model(modelId)
                    .reply(truncate(reply, 200))
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();
        } catch (Exception e) {
            log.warn("模型测试失败: provider={}, model={}, err={}", providerKey, modelId, e.getMessage());
            return LlmModelTestResponse.builder()
                    .available(false)
                    .provider(providerKey)
                    .model(modelId)
                    .latencyMs(System.currentTimeMillis() - start)
                    .errorMessage(truncate(e.getMessage(), 500))
                    .build();
        }
    }

    private String chatInternal(String systemPrompt,
                                String userPrompt,
                                String providerKey,
                                String modelId,
                                int maxRetries,
                                int maxTokens,
                                double temperature) {
        ProviderConfig provider = resolveProvider(providerKey);
        String resolvedModel = resolveModelId(provider, modelId);

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        );

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", resolvedModel);
        requestBody.put("messages", messages);
        requestBody.put("temperature", temperature);
        requestBody.put("max_tokens", maxTokens);

        final String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(requestBody);
        } catch (IOException e) {
            throw new BusinessException("构造 LLM 请求失败: " + e.getMessage());
        }

        String chatUrl = buildChatUrl(provider.getBaseUrl());
        log.info("调用 LLM: provider={}, model={}, url={}", provider.getName(), resolvedModel, chatUrl);

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

            try (Response response = httpClient.newCall(httpRequest).execute()) {
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
            // 部分推理模型把思考放在 reasoning_content，content 可能为空
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
