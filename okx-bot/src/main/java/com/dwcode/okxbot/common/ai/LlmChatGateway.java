package com.dwcode.okxbot.common.ai;

import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.dto.LlmModelTestResponse;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 统一 LLM Chat 门面（Phase A）。
 * <p>
 * 默认经 LangChain4j {@link ChatModel}；配置 {@code ai.chat-engine=okhttp} 时由
 * {@link LlmChatClient} 走旧 OkHttp 实现。
 * <p>
 * 业务模块（video / aigen / imggen）通过全局 {@link LlmChatClient} 调用，本类承载 langchain4j 路径。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmChatGateway {

    private final ChatModelFactory chatModelFactory;
    private final AiProperties aiProperties;
    private final VideoProperties videoProperties;

    @PostConstruct
    void logEngine() {
        log.info("LLM Chat 引擎: {} (ai.chat-engine)",
                aiProperties.isLangChain4jChatEngine() ? "langchain4j" : "okhttp");
    }

    public boolean useLangChain4j() {
        return aiProperties.isLangChain4jChatEngine();
    }

    /**
     * system + user 完成一次 chat（带 video.llm 默认温度/token/重试）。
     */
    public String chat(String systemPrompt, String userPrompt, String providerKey, String modelId) {
        LlmCallOptions options = LlmCallOptions.builder()
                .temperature(videoProperties.getLlm().getTemperature())
                .maxTokens(videoProperties.getLlm().getMaxTokens())
                .maxRetries(videoProperties.getLlm().getMaxRetries())
                .timeoutSeconds(180)
                .build();
        return chat(systemPrompt, userPrompt, providerKey, modelId, options);
    }

    /**
     * 带自定义 options 的 chat（模型测试可传 maxRetries=0、短 timeout）。
     */
    public String chat(String systemPrompt,
                       String userPrompt,
                       String providerKey,
                       String modelId,
                       LlmCallOptions options) {
        LlmCallOptions opts = options != null ? options : LlmCallOptions.builder().build();
        int maxRetries = opts.getMaxRetries() != null
                ? Math.max(0, opts.getMaxRetries())
                : Math.max(0, videoProperties.getLlm().getMaxRetries());
        long backoffMs = Math.max(200L, videoProperties.getLlm().getRetryBackoffMs());
        long maxBackoffMs = Math.max(backoffMs, videoProperties.getLlm().getRetryMaxBackoffMs());

        ChatModelFactory.ResolvedLlm resolved = chatModelFactory.resolve(providerKey, modelId);
        log.info("调用 LLM(langchain4j): provider={}, model={}", resolved.providerKey(), resolved.modelId());

        BusinessException lastBiz = null;
        RuntimeException lastRt = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                long sleepMs = Math.min(maxBackoffMs, backoffMs * (1L << (attempt - 1)));
                sleepMs = sleepMs + (long) (Math.random() * Math.min(1000, sleepMs / 5));
                log.warn("LLM 瞬时失败，第 {}/{} 次重试，等待 {}ms …", attempt, maxRetries, sleepMs);
                sleepQuietly(sleepMs);
            }
            try {
                ChatModel model = chatModelFactory.create(
                        resolved.providerKey(), resolved.modelId(), opts);
                ChatResponse response = model.chat(
                        SystemMessage.from(nullToEmpty(systemPrompt)),
                        UserMessage.from(nullToEmpty(userPrompt))
                );
                return LlmContentHelper.extractText(response);
            } catch (BusinessException e) {
                // 空内容等业务错误一般不可重试
                if (!isRetryableMessage(e.getMessage()) || attempt >= maxRetries) {
                    throw e;
                }
                lastBiz = e;
                log.error("LLM 业务失败可重试: attempt={}/{}, err={}", attempt, maxRetries, e.getMessage());
            } catch (RuntimeException e) {
                lastRt = e;
                boolean retryable = isRetryableMessage(e.getMessage());
                log.error("LLM 调用异常: attempt={}/{}, retryable={}, err={}",
                        attempt, maxRetries, retryable, e.getMessage());
                if (!retryable || attempt >= maxRetries) {
                    throw new BusinessException("LLM 调用异常"
                            + (attempt > 0 ? "（已重试 " + attempt + " 次）" : "")
                            + ": " + e.getMessage());
                }
            }
        }

        if (lastBiz != null) {
            throw lastBiz;
        }
        throw new BusinessException("LLM 调用异常: " + (lastRt != null ? lastRt.getMessage() : "unknown"));
    }

    /**
     * 连通性测试：短提示 + 不重试 + 短超时。
     */
    public LlmModelTestResponse testModel(String providerKey, String modelId) {
        long start = System.currentTimeMillis();
        int timeoutSec = Math.max(1, videoProperties.getLlm().getTestTimeoutSeconds());
        try {
            String reply = chat(
                    "你是一个简洁的助手。",
                    "请只回复：OK",
                    providerKey,
                    modelId,
                    LlmCallOptions.builder()
                            .temperature(0.1)
                            .maxTokens(32)
                            .maxRetries(0)
                            .timeoutSeconds(timeoutSec)
                            .build()
            );
            return LlmModelTestResponse.builder()
                    .available(true)
                    .provider(providerKey)
                    .model(modelId)
                    .reply(LlmContentHelper.truncate(reply, 200))
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            String msg = e.getMessage() != null ? e.getMessage() : "unknown";
            if (isTimeoutError(msg) || cost >= timeoutSec * 1000L - 50) {
                msg = "超过 " + timeoutSec + " 秒无响应，判定不可用";
            }
            log.warn("模型测试失败(langchain4j): provider={}, model={}, {}ms, err={}",
                    providerKey, modelId, cost, msg);
            return LlmModelTestResponse.builder()
                    .available(false)
                    .provider(providerKey)
                    .model(modelId)
                    .latencyMs(cost)
                    .errorMessage(LlmContentHelper.truncate(msg, 500))
                    .build();
        }
    }

    private static boolean isRetryableMessage(String msg) {
        if (msg == null || msg.isBlank()) {
            return false;
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("timeout")
                || lower.contains("timed out")
                || lower.contains("429")
                || lower.contains("503")
                || lower.contains("502")
                || lower.contains("504")
                || lower.contains("408")
                || lower.contains("resourceexhausted")
                || lower.contains("all workers are busy")
                || lower.contains("please retry")
                || lower.contains("rate limit")
                || lower.contains("too many requests")
                || lower.contains("temporarily unavailable")
                || lower.contains("connection reset")
                || lower.contains("connection refused");
    }

    private static boolean isTimeoutError(String msg) {
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("timeout")
                || lower.contains("timed out")
                || lower.contains("deadline")
                || lower.contains("超时");
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("LLM 重试等待被中断");
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
