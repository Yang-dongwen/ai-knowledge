package com.dwcode.okxbot.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.chat.config.AiProperties.ProviderConfig;
import com.dwcode.okxbot.video.service.AiModelConfigService;
import com.dwcode.okxbot.chat.dto.ChatMessageDTO;
import com.dwcode.okxbot.chat.dto.ChatRequest;
import com.dwcode.okxbot.chat.entity.ChatConversationEntity;
import com.dwcode.okxbot.chat.entity.ChatMessageEntity;
import com.dwcode.okxbot.chat.mapper.ChatConversationMapper;
import com.dwcode.okxbot.chat.mapper.ChatMessageMapper;
import com.dwcode.okxbot.okx.service.OkxConfigService;
import com.dwcode.okxbot.strategy.entity.StrategyConfigEntity;
import com.dwcode.okxbot.strategy.entity.StrategyRunLogEntity;
import com.dwcode.okxbot.strategy.mapper.StrategyConfigMapper;
import com.dwcode.okxbot.strategy.mapper.StrategyRunLogMapper;
import com.dwcode.okxbot.system.service.SystemStateService;
import com.dwcode.okxbot.trading.position.entity.PositionEntity;
import com.dwcode.okxbot.trading.position.service.PositionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * AI 聊天服务。
 *
 * 职责：
 * 1. 会话管理（创建/查询/删除）
 * 2. 消息存储
 * 3. 构建上下文 Prompt
 * 4. 调用 AI API 生成回复（支持多供应商多模型）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final StrategyConfigMapper strategyConfigMapper;
    private final StrategyRunLogMapper strategyRunLogMapper;
    private final PositionService positionService;
    private final OkxConfigService okxConfigService;
    private final SystemStateService systemStateService;
    private final AiProperties aiProperties;
    private final AiModelConfigService aiModelConfigService;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    // ==================== 模型列表 ====================

    /**
     * 获取可用供应商及模型列表（数据库 ai_model_config + yml 中有 api-key 的供应商）。
     */
    public List<Map<String, Object>> listAvailableModels() {
        return aiModelConfigService.listEnabledGroupedByProvider();
    }

    // ==================== 会话管理 ====================

    /**
     * 获取会话列表，按更新时间倒序。
     */
    public List<ChatConversationEntity> listConversations() {
        return conversationMapper.selectList(
                new LambdaQueryWrapper<ChatConversationEntity>()
                        .orderByDesc(ChatConversationEntity::getUpdatedAt)
        );
    }

    /**
     * 创建新会话。
     */
    public ChatConversationEntity createConversation(String title, String provider, String model) {
        ChatConversationEntity entity = new ChatConversationEntity();
        entity.setTitle(title != null ? title : "新对话");
        if (provider != null && !provider.isEmpty()) {
            entity.setProvider(provider);
        } else {
            // 使用默认供应商的 key
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
     * 删除会话及其消息。
     */
    public void deleteConversation(Long conversationId) {
        messageMapper.delete(
                new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getConversationId, conversationId)
        );
        conversationMapper.deleteById(conversationId);
    }

    // ==================== 消息管理 ====================

    /**
     * 获取会话消息列表。
     */
    public List<ChatMessageDTO> getMessages(Long conversationId) {
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
        Long conversationId = request.getConversationId();

        // 解析供应商和模型
        ProviderConfig providerConfig = resolveProvider(request.getProvider());
        String modelId = resolveModelId(providerConfig, request.getModel());

        // 新建会话
        if (conversationId == null) {
            String providerKey = request.getProvider();
            if (providerKey == null || providerKey.isEmpty()) {
                // 使用默认供应商的 key
                List<Map.Entry<String, ProviderConfig>> available = aiProperties.getAllAvailableProviders();
                if (!available.isEmpty()) {
                    providerKey = available.get(0).getKey();
                }
            }
            ChatConversationEntity conv = createConversation(
                    request.getMessage().length() > 20 ? request.getMessage().substring(0, 20) : request.getMessage(),
                    providerKey, modelId);
            conversationId = conv.getId();
        } else {
            // 已有会话：如果前端指定了新的 provider/model，更新会话
            if (request.getProvider() != null || request.getModel() != null) {
                ChatConversationEntity conv = conversationMapper.selectById(conversationId);
                if (conv != null) {
                    if (request.getProvider() != null) {
                        conv.setProvider(request.getProvider());
                    }
                    if (request.getModel() != null) {
                        conv.setModel(request.getModel());
                    }
                    conv.setUpdatedAt(LocalDateTime.now());
                    conversationMapper.updateById(conv);
                }
            }
        }

        // 保存用户消息
        ChatMessageEntity userMsg = new ChatMessageEntity();
        userMsg.setConversationId(conversationId);
        userMsg.setRole("user");
        userMsg.setContent(request.getMessage());
        userMsg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(userMsg);

        // 调用 AI
        String aiReply = callAiApi(conversationId, providerConfig, modelId);

        // 保存 AI 回复
        ChatMessageEntity assistantMsg = new ChatMessageEntity();
        assistantMsg.setConversationId(conversationId);
        assistantMsg.setRole("assistant");
        assistantMsg.setContent(aiReply);
        assistantMsg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(assistantMsg);

        // 更新会话标题和时间
        ChatConversationEntity conv = conversationMapper.selectById(conversationId);
        if (conv != null && "新对话".equals(conv.getTitle())) {
            conv.setTitle(request.getMessage().length() > 20
                    ? request.getMessage().substring(0, 20) : request.getMessage());
        }
        if (conv != null) {
            conv.setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(conv);
        }

        // 构建返回
        Map<String, Object> result = new HashMap<>();
        result.put("conversationId", String.valueOf(conversationId));
        result.put("reply", toDTO(assistantMsg));
        return result;
    }

    /**
     * 发送消息并通过 SSE 流式返回 AI 回复。
     *
     * SSE 事件：
     * - meta: 会话元信息 {"conversationId":"xxx"}
     * - delta: AI 回复增量 {"content":"xxx"}
     * - done: 流式结束 {"messageId":"xxx"}
     * - error: 错误信息 {"message":"xxx"}
     */
    public void sendMessageStream(ChatRequest request, SseEmitter emitter) {
        streamExecutor.execute(() -> {
            try {
                Long conversationId = request.getConversationId();

                // 解析供应商和模型
                ProviderConfig providerConfig = resolveProvider(request.getProvider());
                String modelId = resolveModelId(providerConfig, request.getModel());

                // 新建会话
                if (conversationId == null) {
                    String providerKey = request.getProvider();
                    if (providerKey == null || providerKey.isEmpty()) {
                        List<Map.Entry<String, ProviderConfig>> available = aiProperties.getAllAvailableProviders();
                        if (!available.isEmpty()) {
                            providerKey = available.get(0).getKey();
                        }
                    }
                    ChatConversationEntity conv = createConversation(
                            request.getMessage().length() > 20 ? request.getMessage().substring(0, 20) : request.getMessage(),
                            providerKey, modelId);
                    conversationId = conv.getId();
                } else {
                    // 已有会话：如果前端指定了新的 provider/model，更新会话
                    if (request.getProvider() != null || request.getModel() != null) {
                        ChatConversationEntity conv = conversationMapper.selectById(conversationId);
                        if (conv != null) {
                            if (request.getProvider() != null) {
                                conv.setProvider(request.getProvider());
                            }
                            if (request.getModel() != null) {
                                conv.setModel(request.getModel());
                            }
                            conv.setUpdatedAt(LocalDateTime.now());
                            conversationMapper.updateById(conv);
                        }
                    }
                }

                // 保存用户消息
                ChatMessageEntity userMsg = new ChatMessageEntity();
                userMsg.setConversationId(conversationId);
                userMsg.setRole("user");
                userMsg.setContent(request.getMessage());
                userMsg.setCreatedAt(LocalDateTime.now());
                messageMapper.insert(userMsg);

                // 发送 meta 事件（会话ID）
                Map<String, Object> meta = new HashMap<>();
                meta.put("conversationId", String.valueOf(conversationId));
                emitter.send(SseEmitter.event().name("meta").data(objectMapper.writeValueAsString(meta)));

                // 流式调用 AI
                String aiReply = callAiApiStream(conversationId, providerConfig, modelId, emitter);

                // 保存 AI 回复
                ChatMessageEntity assistantMsg = new ChatMessageEntity();
                assistantMsg.setConversationId(conversationId);
                assistantMsg.setRole("assistant");
                assistantMsg.setContent(aiReply);
                assistantMsg.setCreatedAt(LocalDateTime.now());
                messageMapper.insert(assistantMsg);

                // 更新会话标题和时间
                ChatConversationEntity conv = conversationMapper.selectById(conversationId);
                if (conv != null && "新对话".equals(conv.getTitle())) {
                    conv.setTitle(request.getMessage().length() > 20
                            ? request.getMessage().substring(0, 20) : request.getMessage());
                }
                if (conv != null) {
                    conv.setUpdatedAt(LocalDateTime.now());
                    conversationMapper.updateById(conv);
                }

                // 发送 done 事件
                Map<String, Object> done = new HashMap<>();
                done.put("messageId", String.valueOf(assistantMsg.getId()));
                emitter.send(SseEmitter.event().name("done").data(objectMapper.writeValueAsString(done)));
                emitter.complete();

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
            }
        });
    }

    // ==================== 供应商/模型解析 ====================

    /**
     * 根据 request 中的 provider 标识或默认配置解析供应商。
     */
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

    /**
     * 解析模型 ID，若未指定则使用供应商第一个模型。
     */
    private String resolveModelId(ProviderConfig provider, String modelId) {
        if (modelId != null && !modelId.isEmpty()) {
            return modelId;
        }
        if (provider != null && provider.getModels() != null && !provider.getModels().isEmpty()) {
            return provider.getModels().get(0).getId();
        }
        return "gpt-4o-mini";
    }

    // ==================== AI 调用 ====================

    /**
     * 构建 Prompt 并调用 AI API。
     */
    private String callAiApi(Long conversationId, ProviderConfig provider, String modelId) {
        // 检查供应商配置
        if (provider == null || provider.getApiKey() == null || provider.getApiKey().isEmpty()) {
            return buildOfflineReply();
        }

        try {
            // 构建上下文
            String systemPrompt = buildSystemPrompt();

            // 获取历史消息
            List<ChatMessageEntity> historyMessages = messageMapper.selectList(
                    new LambdaQueryWrapper<ChatMessageEntity>()
                            .eq(ChatMessageEntity::getConversationId, conversationId)
                            .orderByDesc(ChatMessageEntity::getCreatedAt)
                            .last("LIMIT " + aiProperties.getMaxContextMessages())
            );
            historyMessages.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));

            // 构建请求消息
            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> sysMsg = new HashMap<>();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.add(sysMsg);

            for (ChatMessageEntity msg : historyMessages) {
                Map<String, String> m = new HashMap<>();
                m.put("role", msg.getRole());
                m.put("content", msg.getContent());
                messages.add(m);
            }

            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelId);
            requestBody.put("messages", messages);
            requestBody.put("temperature", 0.7);
            requestBody.put("max_tokens", 2000);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            // 拼接 API URL
            String apiUrl = provider.getBaseUrl();
            if (!apiUrl.endsWith("/")) {
                apiUrl += "/";
            }
            // 大部分 OpenAI 兼容接口的完整路径是 base-url + chat/completions
            // 如果 baseUrl 已经包含 /v1，则拼接 chat/completions
            // 如果 baseUrl 不包含路径，则拼接 v1/chat/completions
            String chatUrl;
            if (apiUrl.endsWith("/v1/")) {
                chatUrl = apiUrl + "chat/completions";
            } else if (apiUrl.contains("/v1")) {
                chatUrl = apiUrl + (apiUrl.endsWith("/") ? "" : "/") + "chat/completions";
            } else {
                chatUrl = apiUrl + "v1/chat/completions";
            }

            log.info("调用 AI API: provider={}, model={}, url={}", provider.getName(), modelId, chatUrl);

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
            log.error("AI API 调用异常: provider={}, model={}", provider.getName(), modelId, e);
            return "抱歉，AI 服务出现异常，请稍后重试。";
        }
    }

    /**
     * 流式调用 AI API，逐步将增量内容通过 SSE 推送给前端。
     * 返回完整的 AI 回复内容（用于持久化）。
     */
    private String callAiApiStream(Long conversationId, ProviderConfig provider, String modelId, SseEmitter emitter) {
        // 检查供应商配置
        if (provider == null || provider.getApiKey() == null || provider.getApiKey().isEmpty()) {
            String offlineReply = buildOfflineReply();
            try {
                Map<String, Object> delta = new HashMap<>();
                delta.put("content", offlineReply);
                emitter.send(SseEmitter.event().name("delta").data(objectMapper.writeValueAsString(delta)));
            } catch (Exception ignored) {}
            return offlineReply;
        }

        try {
            // 构建上下文
            String systemPrompt = buildSystemPrompt();

            // 获取历史消息
            List<ChatMessageEntity> historyMessages = messageMapper.selectList(
                    new LambdaQueryWrapper<ChatMessageEntity>()
                            .eq(ChatMessageEntity::getConversationId, conversationId)
                            .orderByDesc(ChatMessageEntity::getCreatedAt)
                            .last("LIMIT " + aiProperties.getMaxContextMessages())
            );
            historyMessages.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));

            // 构建请求消息
            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> sysMsg = new HashMap<>();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.add(sysMsg);

            for (ChatMessageEntity msg : historyMessages) {
                Map<String, String> m = new HashMap<>();
                m.put("role", msg.getRole());
                m.put("content", msg.getContent());
                messages.add(m);
            }

            // 构建请求体（启用 stream）
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelId);
            requestBody.put("messages", messages);
            requestBody.put("temperature", 0.7);
            requestBody.put("max_tokens", 2000);
            requestBody.put("stream", true);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            // 拼接 API URL
            String chatUrl = buildChatUrl(provider.getBaseUrl());

            log.info("流式调用 AI API: provider={}, model={}, url={}", provider.getName(), modelId, chatUrl);

            Request httpRequest = new Request.Builder()
                    .url(chatUrl)
                    .addHeader("Authorization", "Bearer " + provider.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null ? response.body().string() : "";
                    log.error("AI API 流式请求失败: provider={}, model={}, status={}, body={}",
                            provider.getName(), modelId, response.code(), errBody);
                    String errorMsg = "抱歉，AI 服务暂时不可用，请稍后重试。";
                    Map<String, Object> delta = new HashMap<>();
                    delta.put("content", errorMsg);
                    emitter.send(SseEmitter.event().name("delta").data(objectMapper.writeValueAsString(delta)));
                    return errorMsg;
                }

                // 逐行读取 SSE 流
                StringBuilder fullContent = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body().byteStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) continue;
                        if (!line.startsWith("data: ")) continue;

                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) break;

                        try {
                            JsonNode chunk = objectMapper.readTree(data);
                            JsonNode choices = chunk.path("choices");
                            if (choices.isArray() && !choices.isEmpty()) {
                                JsonNode delta2 = choices.get(0).path("delta");
                                String content = delta2.path("content").asText("");
                                if (!content.isEmpty()) {
                                    fullContent.append(content);
                                    // 推送增量内容给前端
                                    Map<String, Object> deltaEvent = new HashMap<>();
                                    deltaEvent.put("content", content);
                                    emitter.send(SseEmitter.event().name("delta")
                                            .data(objectMapper.writeValueAsString(deltaEvent)));
                                }
                            }
                        } catch (Exception parseEx) {
                            log.debug("解析流式数据行失败: {}", data, parseEx);
                        }
                    }
                }

                if (fullContent.isEmpty()) {
                    String fallback = "抱歉，AI 未返回有效回复。";
                    Map<String, Object> delta = new HashMap<>();
                    delta.put("content", fallback);
                    emitter.send(SseEmitter.event().name("delta").data(objectMapper.writeValueAsString(delta)));
                    return fallback;
                }

                return fullContent.toString();
            }
        } catch (Exception e) {
            log.error("AI API 流式调用异常: provider={}, model={}", provider.getName(), modelId, e);
            String errorMsg = "抱歉，AI 服务出现异常，请稍后重试。";
            try {
                Map<String, Object> delta = new HashMap<>();
                delta.put("content", errorMsg);
                emitter.send(SseEmitter.event().name("delta").data(objectMapper.writeValueAsString(delta)));
            } catch (Exception ignored) {}
            return errorMsg;
        }
    }

    /**
     * 构建 AI API 的 chat/completions URL。
     */
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
     * 构建系统上下文 Prompt，包含当前交易系统状态。
     */
    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 OKX 自动交易助手的 AI 顾问，专注于加密货币交易分析与建议。");
        sb.append("你的职责是基于系统中的实时数据，为用户提供持仓分析、策略建议、风险评估和市场解读。\n\n");

        // 系统状态
        try {
            String status = systemStateService.getSystemStatus();
            sb.append("【系统状态】运行状态: ").append(status).append("\n");
        } catch (Exception e) {
            sb.append("【系统状态】无法获取\n");
        }

        // 账户余额
        try {
            JsonNode balanceData = okxConfigService.queryBalance();
            if (balanceData != null && balanceData.isArray() && !balanceData.isEmpty()) {
                JsonNode details = balanceData.get(0).path("details");
                if (details.isArray()) {
                    for (JsonNode detail : details) {
                        if ("USDT".equals(detail.path("ccy").asText())) {
                            sb.append("【账户信息】USDT 可用余额: ").append(detail.path("availBal").asText("0"));
                            sb.append(", 总权益: ").append(detail.path("eq").asText("0")).append("\n");
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            sb.append("【账户信息】无法获取\n");
        }

        // 策略信息
        try {
            List<StrategyConfigEntity> strategies = strategyConfigMapper.selectList(null);
            long enabledCount = strategies.stream().filter(s -> s.getEnabled() == 1).count();
            sb.append("【策略信息】共 ").append(strategies.size()).append(" 个策略，其中 ")
                    .append(enabledCount).append(" 个已启用\n");
            for (StrategyConfigEntity s : strategies) {
                sb.append("  - ").append(s.getStrategyName())
                        .append(": ").append(s.getSymbol()).append(" ").append(s.getTimeframe())
                        .append(" (快线").append(s.getFastPeriod()).append("/慢线").append(s.getSlowPeriod())
                        .append(") ").append(s.getEnabled() == 1 ? "已启用" : "已停用").append("\n");
            }
        } catch (Exception e) {
            sb.append("【策略信息】无法获取\n");
        }

        // 持仓信息
        try {
            List<PositionEntity> positions = positionService.listPositions();
            sb.append("【持仓信息】共 ").append(positions.size()).append(" 个持仓\n");
            for (PositionEntity p : positions) {
                BigDecimal pnl = p.getUnrealizedPnl() != null ? p.getUnrealizedPnl() : BigDecimal.ZERO;
                sb.append("  - ").append(p.getSymbol())
                        .append(": 数量 ").append(p.getQuantity().setScale(6, RoundingMode.HALF_UP))
                        .append(", 均价 ").append(p.getAvgPrice().setScale(2, RoundingMode.HALF_UP))
                        .append(", 现价 ").append(p.getCurrentPrice().setScale(2, RoundingMode.HALF_UP))
                        .append(", 浮动盈亏 ").append(pnl.setScale(2, RoundingMode.HALF_UP)).append(" USDT\n");
            }
        } catch (Exception e) {
            sb.append("【持仓信息】无法获取\n");
        }

        // 最近运行日志
        try {
            List<StrategyRunLogEntity> recentLogs = strategyRunLogMapper.selectList(
                    new LambdaQueryWrapper<StrategyRunLogEntity>()
                            .orderByDesc(StrategyRunLogEntity::getCreatedAt)
                            .last("LIMIT 5")
            );
            if (!recentLogs.isEmpty()) {
                sb.append("【最近策略日志】\n");
                for (StrategyRunLogEntity l : recentLogs) {
                    sb.append("  - ").append(l.getSymbol()).append(" ").append(l.getTimeframe())
                            .append(": 信号=").append(l.getTradeSignal())
                            .append(", 动作=").append(l.getAction())
                            .append(", 收盘价=").append(l.getClosePrice() != null ? l.getClosePrice().toPlainString() : "N/A")
                            .append(", 说明=").append(l.getMessage() != null ? l.getMessage() : "").append("\n");
                }
            }
        } catch (Exception e) {
            // 忽略
        }

        sb.append("\n请基于以上实时数据，结合用户的问题，提供专业的交易分析和建议。");
        sb.append("回复时请使用中文，条理清晰，必要时用数字列表格式。");
        sb.append("如果涉及投资建议，请提醒用户注意风险。");

        return sb.toString();
    }

    /**
     * AI 未配置时返回离线默认回复。
     */
    private String buildOfflineReply() {
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ AI 服务未配置或不可用，当前为离线模式。\n\n");

        // 尝试提供基本数据
        try {
            String status = systemStateService.getSystemStatus();
            sb.append("📊 系统状态: ").append(status).append("\n");
        } catch (Exception ignored) {}

        try {
            List<PositionEntity> positions = positionService.listPositions();
            sb.append("📈 当前持仓数: ").append(positions.size()).append("\n");
        } catch (Exception ignored) {}

        try {
            List<StrategyConfigEntity> strategies = strategyConfigMapper.selectList(null);
            long enabled = strategies.stream().filter(s -> s.getEnabled() == 1).count();
            sb.append("📋 策略: 共 ").append(strategies.size()).append(" 个，已启用 ").append(enabled).append(" 个\n");
        } catch (Exception ignored) {}

        // 列出可用模型（数据库 ai_model_config）
        List<Map<String, Object>> availableModels = aiModelConfigService.listEnabledGroupedByProvider();
        if (availableModels.isEmpty()) {
            sb.append("\n暂无可用模型。请在 application.yml 配置供应商 api-key，并在「模型管理」中添加模型。");
        } else {
            sb.append("\n当前可用模型: ");
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