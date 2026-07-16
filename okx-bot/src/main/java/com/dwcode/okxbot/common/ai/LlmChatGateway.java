package com.dwcode.okxbot.common.ai;

import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.dto.LlmModelTestResponse;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 统一 LLM Chat 门面（Phase A）。
 * <p>
 * 默认经 LangChain4j {@link ChatModel}；配置 {@code ai.chat-engine=okhttp} 时由
 * {@link LlmChatClient} 走旧 OkHttp 实现。
 * <p>
 * 业务模块（video / aigen / imggen / chat）通过本类或 {@link LlmChatClient} 调用 langchain4j 路径。
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
     * 多轮消息 chat（AI 对话非流式）。
     */
    public String chatMessages(List<ChatMessage> messages,
                               String providerKey,
                               String modelId,
                               LlmCallOptions options) {
        if (messages == null || messages.isEmpty()) {
            throw new BusinessException("LLM messages 不能为空");
        }
        LlmCallOptions opts = options != null ? options : LlmCallOptions.builder().build();
        ChatModelFactory.ResolvedLlm resolved = chatModelFactory.resolve(providerKey, modelId);
        log.info("调用 LLM(langchain4j multi): provider={}, model={}, messages={}",
                resolved.providerKey(), resolved.modelId(), messages.size());
        ChatModel model = chatModelFactory.create(resolved.providerKey(), resolved.modelId(), opts);
        ChatResponse response = model.chat(messages);
        return LlmContentHelper.extractText(response);
    }

    /**
     * 多轮流式 chat（AI 对话 SSE）。
     * <p>
     * {@code onPartial} 在每个 token 增量时回调；方法阻塞至完成或失败。
     * <p>
     * <b>超时语义（空闲超时，非总时长）</b>：
     * {@link LlmCallOptions#getTimeoutSeconds()} 表示「流式过程中连续无任何输出」的秒数；
     * 有 token 持续输出时可以超过该秒数，仅当空闲达到阈值才中断。
     * 底层 HTTP readTimeout 与应用层 idle watchdog 双重保障。
     *
     * @return 完整助手文本
     */
    public String chatStream(List<ChatMessage> messages,
                             String providerKey,
                             String modelId,
                             LlmCallOptions options,
                             Consumer<String> onPartial) {
        if (messages == null || messages.isEmpty()) {
            throw new BusinessException("LLM messages 不能为空");
        }
        LlmCallOptions opts = options != null ? options : LlmCallOptions.builder().build();
        // 空闲超时：流式期间两次输出之间的最长等待
        int idleSec = opts.getTimeoutSeconds() != null
                ? Math.max(1, opts.getTimeoutSeconds())
                : 20;

        ChatModelFactory.ResolvedLlm resolved = chatModelFactory.resolve(providerKey, modelId);
        log.info("调用 LLM(langchain4j stream): provider={}, model={}, messages={}, idleTimeout={}s",
                resolved.providerKey(), resolved.modelId(), messages.size(), idleSec);

        // HTTP readTimeout = 空闲超时（有数据持续到达时不会因总时长被切断）
        LlmCallOptions streamOpts = LlmCallOptions.builder()
                .temperature(opts.getTemperature())
                .maxTokens(opts.getMaxTokens())
                .maxRetries(0)
                .timeoutSeconds(idleSec)
                .responseFormat(opts.getResponseFormat())
                .build();

        StreamingChatModel streaming = chatModelFactory.createStreaming(
                resolved.providerKey(), resolved.modelId(), streamOpts);

        StringBuilder full = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicLong lastOutputAtMs = new AtomicLong(System.currentTimeMillis());
        AtomicBoolean finished = new AtomicBoolean(false);

        // 应用层空闲看门狗：连续 idleSec 无 token 则强制结束（不限制总生成时长）
        java.util.concurrent.ScheduledExecutorService idleWatch =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "llm-stream-idle-watch");
                    t.setDaemon(true);
                    return t;
                });
        idleWatch.scheduleAtFixedRate(() -> {
            if (finished.get()) {
                return;
            }
            long idleMs = System.currentTimeMillis() - lastOutputAtMs.get();
            if (idleMs >= idleSec * 1000L) {
                log.warn("流式空闲超时: idle={}ms, threshold={}s, partialChars={}",
                        idleMs, idleSec, full.length());
                errorRef.compareAndSet(null,
                        new BusinessException("模型 " + idleSec + " 秒未响应，已强制停止。"));
                finished.set(true);
                done.countDown();
            }
        }, idleSec, 1, TimeUnit.SECONDS);

        try {
            streaming.chat(messages, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    if (partialResponse == null || partialResponse.isEmpty()) {
                        return;
                    }
                    // 有输出则刷新空闲时钟
                    lastOutputAtMs.set(System.currentTimeMillis());
                    full.append(partialResponse);
                    if (onPartial != null) {
                        onPartial.accept(partialResponse);
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    try {
                        if (full.isEmpty() && response != null) {
                            String text = LlmContentHelper.extractText(response);
                            if (text != null && !text.isEmpty()) {
                                lastOutputAtMs.set(System.currentTimeMillis());
                                full.append(text);
                                if (onPartial != null) {
                                    onPartial.accept(text);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.debug("流式完成时 extractText 跳过: {}", e.getMessage());
                    } finally {
                        finished.set(true);
                        done.countDown();
                    }
                }

                @Override
                public void onError(Throwable error) {
                    // 映射底层读超时为统一空闲文案
                    if (isTimeoutError(error != null ? error.getMessage() : null)) {
                        errorRef.compareAndSet(null,
                                new BusinessException("模型 " + idleSec + " 秒未响应，已强制停止。"));
                    } else {
                        errorRef.compareAndSet(null, error);
                    }
                    finished.set(true);
                    done.countDown();
                }
            });

            // 总等待上限仅作兜底（约 30 分钟），正常长回复靠「空闲超时」打断，而非总时长 20s
            boolean completed = done.await(30, TimeUnit.MINUTES);
            if (!completed) {
                throw new BusinessException("模型响应时间过长，已强制停止。");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("LLM 流式调用被中断");
        } finally {
            finished.set(true);
            idleWatch.shutdownNow();
        }

        Throwable err = errorRef.get();
        if (err != null) {
            if (err instanceof RuntimeException re) {
                throw re;
            }
            throw new BusinessException("LLM 流式调用异常: " + err.getMessage());
        }
        if (full.isEmpty()) {
            throw new BusinessException("LLM 未返回有效回复");
        }
        return full.toString();
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
