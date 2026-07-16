package com.dwcode.okxbot.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.chat.config.AiProperties.ProviderConfig;
import com.dwcode.okxbot.common.ai.LlmCallOptions;
import com.dwcode.okxbot.common.ai.LlmChatGateway;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.service.AiModelConfigService;
import com.dwcode.okxbot.chat.dto.ChatMessageDTO;
import com.dwcode.okxbot.chat.dto.ChatRequest;
import com.dwcode.okxbot.chat.entity.ChatConversationEntity;
import com.dwcode.okxbot.chat.entity.ChatMessageEntity;
import com.dwcode.okxbot.chat.mapper.ChatConversationMapper;
import com.dwcode.okxbot.chat.mapper.ChatMessageMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 纯 AI 聊天服务（无交易/账户/策略上下文）。
 *
 * <p>职责：
 * <ol>
 *   <li>会话管理（创建/查询/删除）——按登录用户隔离</li>
 *   <li>消息存储</li>
 *   <li>按用户选择的 provider/model 调用 AI（默认 LangChain4j，与三工具同一引擎开关）</li>
 *   <li>SSE 流式回复</li>
 * </ol>
 *
 * <p>出站引擎：{@code ai.chat-engine=langchain4j}（默认）走 {@link LlmChatGateway}；
 * {@code okhttp} 回滚到手写流式客户端。
 * <p>数据隔离：会话挂 {@code user_id}，列表/读写/删除均校验当前用户，互不可见。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final String SYSTEM_PROMPT = """
            你是一个通用 AI 助手，专注于自然对话、知识问答、写作协助与逻辑推理。
            请用用户使用的语言回复；若用户使用中文，请用中文回复。
            回复条理清晰，必要时使用列表或分段。
            """.stripIndent().trim();

    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final AiProperties aiProperties;
    private final AiModelConfigService aiModelConfigService;
    private final LlmChatGateway llmChatGateway;

    /** 按 ai.response-timeout-seconds 构建；未响应即中断 */
    private OkHttpClient httpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    @PostConstruct
    void initHttpClient() {
        int sec = Math.max(1, aiProperties.getResponseTimeoutSeconds());
        // readTimeout = 空闲无数据超时（流式两次 chunk 之间）；不设 callTimeout，避免总时长切断
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(sec, TimeUnit.SECONDS)
                .readTimeout(sec, TimeUnit.SECONDS)
                .writeTimeout(sec, TimeUnit.SECONDS)
                .build();
        log.info("聊天 HTTP 客户端空闲超时: idleTimeoutSeconds={}（流式无输出才中断，非总时长）", sec);
    }

    /** 流式空闲超时秒数：连续无 token 输出才中断 */
    private int responseTimeoutSeconds() {
        return Math.max(1, aiProperties.getResponseTimeoutSeconds());
    }

    private String timeoutMessage() {
        return "模型 " + responseTimeoutSeconds() + " 秒未响应，已强制停止。";
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException) {
                return true;
            }
            String name = t.getClass().getName();
            String msg = t.getMessage() != null ? t.getMessage() : "";
            if (name.contains("Timeout") || msg.toLowerCase().contains("timeout")
                    || msg.toLowerCase().contains("timed out")
                    || msg.contains("未响应，已强制停止")) {
                return true;
            }
        }
        return false;
    }

    // ==================== 模型列表 ====================

    /**
     * 获取可用供应商及模型列表（数据库 ai_model_config capability=chat + yml 中有 api-key 的供应商）。
     */
    public List<Map<String, Object>> listAvailableModels() {
        return aiModelConfigService.listEnabledGroupedByProvider();
    }

    // ==================== 会话管理（按用户隔离） ====================

    /**
     * 当前登录用户的会话列表，按更新时间倒序。
     */
    public List<ChatConversationEntity> listConversations() {
        Long userId = SecurityUtils.requireCurrentUserId();
        return conversationMapper.selectList(
                new LambdaQueryWrapper<ChatConversationEntity>()
                        .eq(ChatConversationEntity::getUserId, userId)
                        .orderByDesc(ChatConversationEntity::getUpdatedAt)
        );
    }

    /**
     * 为当前用户创建新会话。
     */
    public ChatConversationEntity createConversation(String title, String provider, String model) {
        Long userId = SecurityUtils.requireCurrentUserId();
        ChatConversationEntity entity = new ChatConversationEntity();
        entity.setUserId(userId);
        entity.setTitle(title != null ? title : "新对话");
        if (provider != null && !provider.isEmpty()) {
            entity.setProvider(provider);
        } else {
            Map.Entry<String, ProviderConfig> defaultEntry = aiProperties.getAllAvailableProviders().stream()
                    .findFirst().orElse(null);
            entity.setProvider(defaultEntry != null ? defaultEntry.getKey() : null);
        }
        if (model != null && !model.isEmpty()) {
            entity.setModel(model);
        } else {
            ProviderConfig defaultProvider = aiProperties.getDefaultProvider();
            entity.setModel(defaultProvider != null && !defaultProvider.getModels().isEmpty()
                    ? defaultProvider.getModels().get(0).getId() : null);
        }
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        conversationMapper.insert(entity);
        return entity;
    }

    /**
     * 删除当前用户拥有的会话及其消息。
     */
    public void deleteConversation(Long conversationId) {
        ChatConversationEntity conv = requireOwnedConversation(conversationId);
        messageMapper.delete(
                new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getConversationId, conv.getId())
        );
        conversationMapper.deleteById(conv.getId());
    }

    // ==================== 消息管理 ====================

    /**
     * 获取当前用户某会话的消息列表。
     */
    public List<ChatMessageDTO> getMessages(Long conversationId) {
        requireOwnedConversation(conversationId);
        List<ChatMessageEntity> entities = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getConversationId, conversationId)
                        .orderByAsc(ChatMessageEntity::getCreatedAt)
        );
        return entities.stream().map(this::toDTO).collect(Collectors.toList());
    }

    /**
     * 发送消息并获取 AI 回复。
     */
    public Map<String, Object> sendMessage(ChatRequest request) {
        Long userId = SecurityUtils.requireCurrentUserId();
        Long conversationId = request.getConversationId();

        ProviderConfig providerConfig = resolveProvider(request.getProvider());
        String modelId = resolveModelId(providerConfig, request.getModel());
        String providerKey = resolveProviderKey(request.getProvider(), providerConfig);

        if (conversationId == null) {
            ChatConversationEntity conv = createConversation(
                    titleFromMessage(request.getMessage()),
                    providerKey, modelId);
            conversationId = conv.getId();
        } else {
            requireOwnedConversation(conversationId, userId);
            updateConversationModel(conversationId, userId, request.getProvider(), request.getModel());
        }

        ChatMessageEntity userMsg = new ChatMessageEntity();
        userMsg.setConversationId(conversationId);
        userMsg.setRole("user");
        userMsg.setContent(request.getMessage());
        userMsg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(userMsg);

        String aiReply = callAiApi(conversationId, providerKey, providerConfig, modelId);

        ChatMessageEntity assistantMsg = new ChatMessageEntity();
        assistantMsg.setConversationId(conversationId);
        assistantMsg.setRole("assistant");
        assistantMsg.setContent(aiReply);
        assistantMsg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(assistantMsg);

        touchConversationTitle(conversationId, userId, request.getMessage());

        Map<String, Object> result = new HashMap<>();
        result.put("conversationId", String.valueOf(conversationId));
        result.put("reply", toDTO(assistantMsg));
        return result;
    }

    /**
     * 发送消息并通过 SSE 流式返回 AI 回复。
     *
     * <p>SSE 事件：
     * <ul>
     *   <li>meta: 会话元信息 {"conversationId":"xxx"}</li>
     *   <li>delta: AI 回复增量 {"content":"xxx"}</li>
     *   <li>done: 流式结束 {"messageId":"xxx"}</li>
     *   <li>error: 错误信息 {"message":"xxx"}</li>
     * </ul>
     */
    public void sendMessageStream(ChatRequest request, SseEmitter emitter) {
        // 线程池不继承 ThreadLocal，显式传递 SecurityContext（含 userId），避免异步阶段鉴权丢失
        SecurityContext securityContext = SecurityContextHolder.getContext();
        streamExecutor.execute(() -> {
            SecurityContextHolder.setContext(securityContext);
            try {
                Long userId = SecurityUtils.requireCurrentUserId();
                Long conversationId = request.getConversationId();

                ProviderConfig providerConfig = resolveProvider(request.getProvider());
                String modelId = resolveModelId(providerConfig, request.getModel());
                String providerKey = resolveProviderKey(request.getProvider(), providerConfig);

                if (conversationId == null) {
                    ChatConversationEntity conv = createConversation(
                            titleFromMessage(request.getMessage()),
                            providerKey, modelId);
                    conversationId = conv.getId();
                } else {
                    requireOwnedConversation(conversationId, userId);
                    updateConversationModel(conversationId, userId, request.getProvider(), request.getModel());
                }

                ChatMessageEntity userMsg = new ChatMessageEntity();
                userMsg.setConversationId(conversationId);
                userMsg.setRole("user");
                userMsg.setContent(request.getMessage());
                userMsg.setCreatedAt(LocalDateTime.now());
                messageMapper.insert(userMsg);

                Map<String, Object> meta = new HashMap<>();
                meta.put("conversationId", String.valueOf(conversationId));
                meta.put("provider", providerKey);
                meta.put("model", modelId);
                emitter.send(SseEmitter.event().name("meta").data(objectMapper.writeValueAsString(meta)));

                String aiReply = callAiApiStream(conversationId, providerKey, providerConfig, modelId, emitter);

                ChatMessageEntity assistantMsg = new ChatMessageEntity();
                assistantMsg.setConversationId(conversationId);
                assistantMsg.setRole("assistant");
                assistantMsg.setContent(aiReply);
                assistantMsg.setCreatedAt(LocalDateTime.now());
                messageMapper.insert(assistantMsg);

                touchConversationTitle(conversationId, userId, request.getMessage());

                Map<String, Object> done = new HashMap<>();
                done.put("messageId", String.valueOf(assistantMsg.getId()));
                emitter.send(SseEmitter.event().name("done").data(objectMapper.writeValueAsString(done)));
                emitter.complete();

            } catch (BusinessException e) {
                log.warn("SSE 业务拒绝: {}", e.getMessage());
                try {
                    Map<String, Object> error = new HashMap<>();
                    error.put("message", e.getMessage() != null ? e.getMessage() : "无权限或请求无效");
                    emitter.send(SseEmitter.event().name("error").data(objectMapper.writeValueAsString(error)));
                    emitter.complete();
                } catch (Exception ex) {
                    emitter.completeWithError(ex);
                }
            } catch (Exception e) {
                log.error("SSE 流式响应异常", e);
                try {
                    Map<String, Object> error = new HashMap<>();
                    error.put("message", "AI 服务出现异常，请稍后重试。");
                    emitter.send(SseEmitter.event().name("error").data(objectMapper.writeValueAsString(error)));
                    emitter.complete();
                } catch (Exception ex) {
                    emitter.completeWithError(ex);
                }
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    /**
     * 校验会话存在且属于当前登录用户。
     */
    private ChatConversationEntity requireOwnedConversation(Long conversationId) {
        return requireOwnedConversation(conversationId, SecurityUtils.requireCurrentUserId());
    }

    private ChatConversationEntity requireOwnedConversation(Long conversationId, Long userId) {
        if (conversationId == null) {
            throw new BusinessException(400, "会话 ID 无效");
        }
        ChatConversationEntity conv = conversationMapper.selectById(conversationId);
        if (conv == null) {
            throw new BusinessException(404, "会话不存在");
        }
        if (conv.getUserId() == null || !conv.getUserId().equals(userId)) {
            // 不暴露「存在但非本人」，统一 404
            throw new BusinessException(404, "会话不存在");
        }
        return conv;
    }

    // ==================== 供应商/模型解析 ====================

    private ProviderConfig resolveProvider(String providerKey) {
        if (providerKey != null && !providerKey.isEmpty()) {
            ProviderConfig config = aiProperties.getProvider(providerKey);
            if (config != null && config.getApiKey() != null && !config.getApiKey().isEmpty()) {
                return config;
            }
            log.warn("供应商 {} 不可用或未配置 apiKey，使用默认供应商", providerKey);
        }
        ProviderConfig defaultProvider = aiProperties.getDefaultProvider();
        if (defaultProvider != null) {
            return defaultProvider;
        }
        return null;
    }

    private String resolveProviderKey(String requestProviderKey, ProviderConfig resolved) {
        if (requestProviderKey != null && !requestProviderKey.isEmpty()
                && aiProperties.getProvider(requestProviderKey) != null) {
            return requestProviderKey;
        }
        if (resolved == null) {
            return requestProviderKey;
        }
        return aiProperties.getAllAvailableProviders().stream()
                .filter(e -> e.getValue() == resolved || e.getValue().equals(resolved))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(requestProviderKey);
    }

    private String resolveModelId(ProviderConfig provider, String modelId) {
        if (modelId != null && !modelId.isEmpty()) {
            return modelId;
        }
        if (provider != null && provider.getModels() != null && !provider.getModels().isEmpty()) {
            return provider.getModels().get(0).getId();
        }
        // 回退：从数据库 chat 模型列表取第一个
        List<Map<String, Object>> grouped = aiModelConfigService.listEnabledGroupedByProvider();
        if (!grouped.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> models = (List<Map<String, Object>>) grouped.get(0).get("models");
            if (models != null && !models.isEmpty() && models.get(0).get("id") != null) {
                return String.valueOf(models.get(0).get("id"));
            }
        }
        return "gpt-4o-mini";
    }

    private void updateConversationModel(Long conversationId, Long userId, String provider, String model) {
        if (provider == null && model == null) {
            return;
        }
        ChatConversationEntity conv = conversationMapper.selectOne(
                new LambdaQueryWrapper<ChatConversationEntity>()
                        .eq(ChatConversationEntity::getId, conversationId)
                        .eq(ChatConversationEntity::getUserId, userId)
        );
        if (conv == null) {
            return;
        }
        if (provider != null && !provider.isEmpty()) {
            conv.setProvider(provider);
        }
        if (model != null && !model.isEmpty()) {
            conv.setModel(model);
        }
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conv);
    }

    private void touchConversationTitle(Long conversationId, Long userId, String message) {
        ChatConversationEntity conv = conversationMapper.selectOne(
                new LambdaQueryWrapper<ChatConversationEntity>()
                        .eq(ChatConversationEntity::getId, conversationId)
                        .eq(ChatConversationEntity::getUserId, userId)
        );
        if (conv == null) {
            return;
        }
        if ("新对话".equals(conv.getTitle())) {
            conv.setTitle(titleFromMessage(message));
        }
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conv);
    }

    private static String titleFromMessage(String message) {
        if (message == null || message.isEmpty()) {
            return "新对话";
        }
        return message.length() > 20 ? message.substring(0, 20) : message;
    }

    // ==================== AI 调用 ====================

    private LlmCallOptions chatCallOptions() {
        return LlmCallOptions.builder()
                .temperature(0.7)
                .maxTokens(2000)
                .maxRetries(0)
                .timeoutSeconds(responseTimeoutSeconds())
                .build();
    }

    private String callAiApi(Long conversationId, String providerKey, ProviderConfig provider, String modelId) {
        if (provider == null || provider.getApiKey() == null || provider.getApiKey().isEmpty()) {
            return buildOfflineReply();
        }

        if (aiProperties.isLangChain4jChatEngine()) {
            return callAiApiLangChain4j(conversationId, providerKey, modelId);
        }
        return callAiApiOkHttp(conversationId, provider, modelId);
    }

    /**
     * 流式调用 AI API，逐步将增量内容通过 SSE 推送给前端。
     * 返回完整的 AI 回复内容（用于持久化）。
     */
    private String callAiApiStream(Long conversationId,
                                   String providerKey,
                                   ProviderConfig provider,
                                   String modelId,
                                   SseEmitter emitter) {
        if (provider == null || provider.getApiKey() == null || provider.getApiKey().isEmpty()) {
            String offlineReply = buildOfflineReply();
            sendStreamDelta(emitter, offlineReply);
            return offlineReply;
        }

        if (aiProperties.isLangChain4jChatEngine()) {
            return callAiApiStreamLangChain4j(conversationId, providerKey, modelId, emitter);
        }
        return callAiApiStreamOkHttp(conversationId, provider, modelId, emitter);
    }

    // ---------- LangChain4j（默认，与三工具共用 ai.chat-engine） ----------

    private String callAiApiLangChain4j(Long conversationId, String providerKey, String modelId) {
        try {
            List<dev.langchain4j.data.message.ChatMessage> messages = buildLangChainMessages(conversationId);
            log.info("聊天调用 langchain4j: provider={}, model={}, messages={}",
                    providerKey, modelId, messages.size());
            return llmChatGateway.chatMessages(messages, providerKey, modelId, chatCallOptions());
        } catch (Exception e) {
            if (isTimeout(e)) {
                log.warn("AI API 超时(langchain4j): provider={}, model={}, timeout={}s",
                        providerKey, modelId, responseTimeoutSeconds());
                return timeoutMessage();
            }
            log.error("AI API 调用异常(langchain4j): provider={}, model={}", providerKey, modelId, e);
            return "抱歉，AI 服务出现异常，请稍后重试。";
        }
    }

    private String callAiApiStreamLangChain4j(Long conversationId,
                                              String providerKey,
                                              String modelId,
                                              SseEmitter emitter) {
        StringBuilder fullContent = new StringBuilder();
        try {
            List<dev.langchain4j.data.message.ChatMessage> messages = buildLangChainMessages(conversationId);
            log.info("聊天流式调用 langchain4j: provider={}, model={}, messages={}",
                    providerKey, modelId, messages.size());

            String full = llmChatGateway.chatStream(
                    messages,
                    providerKey,
                    modelId,
                    chatCallOptions(),
                    token -> {
                        fullContent.append(token);
                        sendStreamDelta(emitter, token);
                    });
            if (full == null || full.isBlank()) {
                String fallback = "抱歉，AI 未返回有效回复。";
                sendStreamDelta(emitter, fallback);
                return fallback;
            }
            return full;
        } catch (Exception e) {
            if (isTimeout(e)) {
                log.warn("AI API 流式超时(langchain4j): provider={}, model={}, timeout={}s, partialChars={}",
                        providerKey, modelId, responseTimeoutSeconds(), fullContent.length());
                return handleStreamTimeout(emitter, fullContent);
            }
            log.error("AI API 流式调用异常(langchain4j): provider={}, model={}", providerKey, modelId, e);
            String errorMsg = "抱歉，AI 服务出现异常，请稍后重试。";
            sendStreamError(emitter, errorMsg);
            return fullContent.length() > 0 ? fullContent + "\n\n" + errorMsg : errorMsg;
        }
    }

    // ---------- OkHttp 回滚（ai.chat-engine=okhttp） ----------

    private String callAiApiOkHttp(Long conversationId, ProviderConfig provider, String modelId) {
        try {
            List<Map<String, String>> messages = buildMessagesMap(conversationId);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelId);
            requestBody.put("messages", messages);
            requestBody.put("temperature", 0.7);
            requestBody.put("max_tokens", 2000);

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            String chatUrl = buildChatUrl(provider.getBaseUrl());

            log.info("调用 AI API(okhttp): provider={}, model={}, url={}", provider.getName(), modelId, chatUrl);

            Request httpRequest = new Request.Builder()
                    .url(chatUrl)
                    .addHeader("Authorization", "Bearer " + provider.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null ? response.body().string() : "";
                    log.error("AI API 请求失败: provider={}, model={}, status={}, body={}",
                            provider.getName(), modelId, response.code(), errBody);
                    return "抱歉，AI 服务暂时不可用，请稍后重试。";
                }

                String respBody = response.body() != null ? response.body().string() : "";
                JsonNode respJson = objectMapper.readTree(respBody);
                JsonNode choices = respJson.path("choices");
                if (choices.isArray() && !choices.isEmpty()) {
                    return choices.get(0).path("message").path("content").asText("");
                }
                return "抱歉，AI 未返回有效回复。";
            }
        } catch (Exception e) {
            if (isTimeout(e)) {
                log.warn("AI API 超时(okhttp): provider={}, model={}, timeout={}s",
                        provider.getName(), modelId, responseTimeoutSeconds());
                return timeoutMessage();
            }
            log.error("AI API 调用异常(okhttp): provider={}, model={}", provider.getName(), modelId, e);
            return "抱歉，AI 服务出现异常，请稍后重试。";
        }
    }

    private String callAiApiStreamOkHttp(Long conversationId,
                                         ProviderConfig provider,
                                         String modelId,
                                         SseEmitter emitter) {
        try {
            List<Map<String, String>> messages = buildMessagesMap(conversationId);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelId);
            requestBody.put("messages", messages);
            requestBody.put("temperature", 0.7);
            requestBody.put("max_tokens", 2000);
            requestBody.put("stream", true);

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            String chatUrl = buildChatUrl(provider.getBaseUrl());

            log.info("流式调用 AI API(okhttp): provider={}, model={}, url={}", provider.getName(), modelId, chatUrl);

            Request httpRequest = new Request.Builder()
                    .url(chatUrl)
                    .addHeader("Authorization", "Bearer " + provider.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                    .build();

            Call call = httpClient.newCall(httpRequest);
            StringBuilder fullContent = new StringBuilder();
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null ? response.body().string() : "";
                    log.error("AI API 流式请求失败: provider={}, model={}, status={}, body={}",
                            provider.getName(), modelId, response.code(), errBody);
                    String errorMsg = "抱歉，AI 服务暂时不可用，请稍后重试。";
                    sendStreamError(emitter, errorMsg);
                    return errorMsg;
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body().byteStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            continue;
                        }
                        if (!line.startsWith("data: ")) {
                            continue;
                        }

                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) {
                            break;
                        }

                        try {
                            JsonNode chunk = objectMapper.readTree(data);
                            JsonNode choices = chunk.path("choices");
                            if (choices.isArray() && !choices.isEmpty()) {
                                JsonNode delta2 = choices.get(0).path("delta");
                                String content = delta2.path("content").asText("");
                                if (!content.isEmpty()) {
                                    fullContent.append(content);
                                    sendStreamDelta(emitter, content);
                                }
                            }
                        } catch (Exception parseEx) {
                            log.debug("解析流式数据行失败: {}", data, parseEx);
                        }
                    }
                }

                if (fullContent.isEmpty()) {
                    String fallback = "抱歉，AI 未返回有效回复。";
                    sendStreamDelta(emitter, fallback);
                    return fallback;
                }

                return fullContent.toString();
            } catch (Exception e) {
                call.cancel();
                if (isTimeout(e)) {
                    log.warn("AI API 流式超时(okhttp): provider={}, model={}, timeout={}s, partialChars={}",
                            provider.getName(), modelId, responseTimeoutSeconds(), fullContent.length());
                    return handleStreamTimeout(emitter, fullContent);
                }
                throw e;
            }
        } catch (Exception e) {
            if (isTimeout(e)) {
                log.warn("AI API 流式超时(okhttp): provider={}, model={}, timeout={}s",
                        provider.getName(), modelId, responseTimeoutSeconds());
                String errorMsg = timeoutMessage();
                sendStreamError(emitter, errorMsg);
                return errorMsg;
            }
            log.error("AI API 流式调用异常(okhttp): provider={}, model={}", provider.getName(), modelId, e);
            String errorMsg = "抱歉，AI 服务出现异常，请稍后重试。";
            sendStreamError(emitter, errorMsg);
            return errorMsg;
        }
    }

    private String handleStreamTimeout(SseEmitter emitter, StringBuilder fullContent) {
        String errorMsg = timeoutMessage();
        if (fullContent.length() > 0) {
            String notice = "\n\n" + errorMsg;
            fullContent.append(notice);
            sendStreamDelta(emitter, notice);
            sendStreamError(emitter, errorMsg);
            return fullContent.toString();
        }
        sendStreamError(emitter, errorMsg);
        return errorMsg;
    }

    private void sendStreamDelta(SseEmitter emitter, String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> delta = new HashMap<>();
            delta.put("content", content);
            emitter.send(SseEmitter.event().name("delta").data(objectMapper.writeValueAsString(delta)));
        } catch (Exception ignored) {
        }
    }

    /** 通过 SSE error 事件通知前端（超时/失败强制停止） */
    private void sendStreamError(SseEmitter emitter, String message) {
        try {
            Map<String, Object> error = new HashMap<>();
            error.put("message", message);
            emitter.send(SseEmitter.event().name("error").data(objectMapper.writeValueAsString(error)));
        } catch (Exception ignored) {
        }
    }

    /**
     * 构建 LangChain4j 多轮消息：system + 近期历史。
     */
    private List<dev.langchain4j.data.message.ChatMessage> buildLangChainMessages(Long conversationId) {
        List<ChatMessageEntity> history = loadHistory(conversationId);
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        for (ChatMessageEntity msg : history) {
            String content = msg.getContent() != null ? msg.getContent() : "";
            if ("assistant".equalsIgnoreCase(msg.getRole())) {
                messages.add(AiMessage.from(content));
            } else {
                messages.add(UserMessage.from(content));
            }
        }
        return messages;
    }

    /**
     * 构建 OkHttp 用 messages：固定 system + 近期历史。
     */
    private List<Map<String, String>> buildMessagesMap(Long conversationId) {
        List<ChatMessageEntity> historyMessages = loadHistory(conversationId);
        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> sysMsg = new HashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", SYSTEM_PROMPT);
        messages.add(sysMsg);

        for (ChatMessageEntity msg : historyMessages) {
            Map<String, String> m = new HashMap<>();
            m.put("role", msg.getRole());
            m.put("content", msg.getContent());
            messages.add(m);
        }
        return messages;
    }

    private List<ChatMessageEntity> loadHistory(Long conversationId) {
        List<ChatMessageEntity> historyMessages = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getConversationId, conversationId)
                        .orderByDesc(ChatMessageEntity::getCreatedAt)
                        .last("LIMIT " + aiProperties.getMaxContextMessages())
        );
        historyMessages.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));
        return historyMessages;
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

    /**
     * AI 未配置时返回离线默认回复（不包含任何交易数据）。
     */
    private String buildOfflineReply() {
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ AI 服务未配置或不可用，当前为离线模式。\n\n");

        List<Map<String, Object>> availableModels = aiModelConfigService.listEnabledGroupedByProvider();
        if (availableModels.isEmpty()) {
            sb.append("暂无可用模型。请在 application.yml 配置供应商 api-key，并在模型管理中添加 Chat 模型。");
        } else {
            sb.append("当前可用模型: ");
            for (Map<String, Object> entry : availableModels) {
                sb.append(entry.get("name")).append(" (");
                @SuppressWarnings("unchecked")
                List<Map<String, String>> models = (List<Map<String, String>>) entry.get("models");
                if (models != null && !models.isEmpty()) {
                    for (Map<String, String> m : models) {
                        sb.append(m.get("name")).append(", ");
                    }
                    sb.setLength(sb.length() - 2);
                }
                sb.append(") ");
            }
            sb.append("\n请检查对应供应商的 API Key 是否有效。");
        }
        return sb.toString();
    }

    private ChatMessageDTO toDTO(ChatMessageEntity entity) {
        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setId(String.valueOf(entity.getId()));
        dto.setRole(entity.getRole());
        dto.setContent(entity.getContent());
        dto.setTimestamp(entity.getCreatedAt());
        return dto;
    }
}
