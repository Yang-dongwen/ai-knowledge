package com.dwcode.okxbot.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.chat.config.AiProperties.ProviderConfig;
import com.dwcode.okxbot.chat.agent.AgentConfirmService;
import com.dwcode.okxbot.chat.agent.ChatAgentOrchestrator;
import com.dwcode.okxbot.chat.agent.ToolContext;
import com.dwcode.okxbot.chat.agent.ToolResult;
import com.dwcode.okxbot.chat.config.AgentProperties;
import com.dwcode.okxbot.chat.stream.ChatStreamHandle;
import com.dwcode.okxbot.chat.stream.ChatStreamRegistry;
import com.dwcode.okxbot.chat.stream.StreamCancelledException;
import com.dwcode.okxbot.common.ai.LlmCallOptions;
import com.dwcode.okxbot.common.ai.LlmChatGateway;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.member.service.MemberStatusService;
import com.dwcode.okxbot.common.ai.AiModelConfigService;
import com.dwcode.okxbot.chat.dto.ChatMessageDTO;
import com.dwcode.okxbot.chat.dto.ChatRequest;
import com.dwcode.okxbot.chat.dto.EditResendRequest;
import com.dwcode.okxbot.chat.dto.UpdateConversationRequest;
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
    private final ChatStreamRegistry chatStreamRegistry;
    private final ChatAgentOrchestrator chatAgentOrchestrator;
    private final AgentConfirmService agentConfirmService;
    private final AgentProperties agentProperties;
    private final MemberStatusService memberStatusService;

    /** 按 ai.response-timeout-seconds 构建；未响应即中断 */
    private OkHttpClient httpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 有界流式线程池，防止会员并发 SSE 打爆线程 */
    private final ExecutorService streamExecutor = new java.util.concurrent.ThreadPoolExecutor(
            4,
            32,
            60L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(64),
            r -> {
                Thread t = new Thread(r, "chat-stream");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
    );

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
     *
     * @param keyword 可选，标题模糊匹配
     */
    public List<ChatConversationEntity> listConversations(String keyword) {
        Long userId = SecurityUtils.requireCurrentUserId();
        LambdaQueryWrapper<ChatConversationEntity> q = new LambdaQueryWrapper<ChatConversationEntity>()
                .eq(ChatConversationEntity::getUserId, userId)
                .orderByDesc(ChatConversationEntity::getUpdatedAt);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            q.like(ChatConversationEntity::getTitle, kw);
        }
        return conversationMapper.selectList(q);
    }

    /** 兼容无参调用 */
    public List<ChatConversationEntity> listConversations() {
        return listConversations(null);
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

    /**
     * 重命名当前用户的会话。
     */
    public ChatConversationEntity renameConversation(Long conversationId, String title) {
        UpdateConversationRequest req = new UpdateConversationRequest();
        req.setTitle(title);
        return updateConversation(conversationId, req);
    }

    /**
     * 更新会话标题 / 模型 / 生成参数 / 系统提示。
     */
    public ChatConversationEntity updateConversation(Long conversationId, UpdateConversationRequest request) {
        Long userId = SecurityUtils.requireCurrentUserId();
        ChatConversationEntity conv = requireOwnedConversation(conversationId, userId);
        if (request == null) {
            return conv;
        }
        if (request.getTitle() != null) {
            String t = request.getTitle().trim();
            if (t.isEmpty()) {
                throw new BusinessException(400, "标题不能为空");
            }
            if (t.length() > 100) {
                t = t.substring(0, 100);
            }
            conv.setTitle(t);
        }
        if (request.getProvider() != null && !request.getProvider().isBlank()) {
            conv.setProvider(request.getProvider().trim());
        }
        if (request.getModel() != null && !request.getModel().isBlank()) {
            conv.setModel(request.getModel().trim());
        }
        if (request.getTemperature() != null) {
            double t = request.getTemperature();
            if (t < 0 || t > 2) {
                throw new BusinessException(400, "temperature 需在 0~2");
            }
            conv.setTemperature(t);
        }
        if (request.getMaxTokens() != null) {
            int m = request.getMaxTokens();
            if (m < 64 || m > 16000) {
                throw new BusinessException(400, "maxTokens 需在 64~16000");
            }
            conv.setMaxTokens(m);
        }
        if (Boolean.TRUE.equals(request.getClearSystemPrompt())) {
            conv.setSystemPrompt(null);
        } else if (request.getSystemPrompt() != null) {
            String sp = request.getSystemPrompt().trim();
            conv.setSystemPrompt(sp.isEmpty() ? null : sp);
        }
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conv);
        return conv;
    }

    // ==================== 消息管理 ====================

    /**
     * 获取当前用户某会话的消息列表。
     */
    public List<ChatMessageDTO> getMessages(Long conversationId) {
        requireOwnedConversation(conversationId);
        // 将 token 已失效但仍挂着 AGENT_CONFIRM 的消息改写为终态，避免刷新后再次出现可确认卡
        agentConfirmService.reconcileStaleConfirms(conversationId);
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
        SecurityContext securityContext = SecurityContextHolder.getContext();
        try {
        streamExecutor.execute(() -> {
            SecurityContextHolder.setContext(securityContext);
            ChatStreamHandle streamHandle = null;
            Long userId = null;
            Long conversationId = null;
            try {
                userId = SecurityUtils.requireCurrentUserId();
                memberStatusService.requireActiveMember(userId);
                conversationId = request.getConversationId();

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

                // 请求级参数写回会话（便于下次打开仍生效）
                applyRequestOptionsToConversation(conversationId, userId, request);

                streamHandle = chatStreamRegistry.register(userId, conversationId);
                bindEmitterCancel(emitter, streamHandle);

                boolean agentMode = Boolean.TRUE.equals(request.getAgentMode())
                        && agentProperties.isEnabled();

                Map<String, Object> meta = new HashMap<>();
                meta.put("conversationId", String.valueOf(conversationId));
                meta.put("streamId", streamHandle.getStreamId());
                meta.put("provider", providerKey);
                meta.put("model", modelId);
                meta.put("agentMode", agentMode);
                emitter.send(SseEmitter.event().name("meta").data(objectMapper.writeValueAsString(meta)));

                ChatConversationEntity convSnap = conversationMapper.selectById(conversationId);
                String aiReply;

                if (agentMode) {
                    aiReply = runAgentTurnAndStream(
                            request.getMessage(),
                            conversationId,
                            userId,
                            providerKey,
                            modelId,
                            streamHandle,
                            emitter);
                    // null 表示回退纯聊天流式
                    if (aiReply == null) {
                        aiReply = callAiApiStream(
                                conversationId, providerKey, providerConfig, modelId,
                                emitter, streamHandle, convSnap, request);
                    }
                } else {
                    aiReply = callAiApiStream(
                            conversationId, providerKey, providerConfig, modelId,
                            emitter, streamHandle, convSnap, request);
                }

                persistAssistantAndComplete(conversationId, userId, request.getMessage(),
                        aiReply, false, emitter);

            } catch (StreamCancelledException e) {
                handleStreamCancelled(conversationId, userId, e, request.getMessage(), emitter);
            } catch (BusinessException e) {
                log.warn("SSE 业务拒绝: {}", e.getMessage());
                sendSseErrorAndComplete(emitter, e.getMessage() != null ? e.getMessage() : "无权限或请求无效");
            } catch (Exception e) {
                log.error("SSE 流式响应异常", e);
                sendSseErrorAndComplete(emitter, "AI 服务出现异常，请稍后重试。");
            } finally {
                if (streamHandle != null) {
                    chatStreamRegistry.unregister(streamHandle);
                }
                SecurityContextHolder.clearContext();
            }
        });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            sendSseErrorAndComplete(emitter, "对话服务繁忙，请稍后再试");
        }
    }

    /**
     * 停止当前用户的活跃流（真取消：后端停止推送并只保留部分内容）。
     *
     * @return true 找到并标记取消
     */
    public boolean stopStream(String streamId, Long conversationId) {
        Long userId = SecurityUtils.requireCurrentUserId();
        if (conversationId != null) {
            // 防止停别人的会话（即使 streamId 猜错）
            requireOwnedConversation(conversationId, userId);
        }
        boolean ok = chatStreamRegistry.cancel(userId, streamId, conversationId);
        log.info("stopStream: userId={}, streamId={}, conv={}, ok={}",
                userId, streamId, conversationId, ok);
        return ok;
    }

    /**
     * 重新生成最后一条 AI 回复（SSE）。
     * <ol>
     *   <li>校验会话归属</li>
     *   <li>删除末尾连续的 assistant 消息</li>
     *   <li>要求此时最后一条为 user</li>
     *   <li>不插入新 user，直接流式生成新 assistant</li>
     * </ol>
     */
    public void regenerateStream(ChatRequest request, SseEmitter emitter) {
        // 门闸在异步线程内校验（与 send 一致）
        SecurityContext securityContext = SecurityContextHolder.getContext();
        try {
        streamExecutor.execute(() -> {
            SecurityContextHolder.setContext(securityContext);
            ChatStreamHandle streamHandle = null;
            Long userId = null;
            Long conversationId = null;
            try {
                userId = SecurityUtils.requireCurrentUserId();
                memberStatusService.requireActiveMember(userId);
                conversationId = request.getConversationId();
                if (conversationId == null) {
                    throw new BusinessException(400, "重新生成需要指定会话");
                }
                requireOwnedConversation(conversationId, userId);

                ProviderConfig providerConfig = resolveProvider(request.getProvider());
                String modelId = resolveModelId(providerConfig, request.getModel());
                String providerKey = resolveProviderKey(request.getProvider(), providerConfig);
                updateConversationModel(conversationId, userId, request.getProvider(), request.getModel());

                List<ChatMessageEntity> history = messageMapper.selectList(
                        new LambdaQueryWrapper<ChatMessageEntity>()
                                .eq(ChatMessageEntity::getConversationId, conversationId)
                                .orderByDesc(ChatMessageEntity::getCreatedAt)
                                .last("LIMIT 20")
                );
                for (ChatMessageEntity msg : history) {
                    if ("assistant".equalsIgnoreCase(msg.getRole())) {
                        messageMapper.deleteById(msg.getId());
                    } else {
                        break;
                    }
                }

                ChatMessageEntity last = messageMapper.selectOne(
                        new LambdaQueryWrapper<ChatMessageEntity>()
                                .eq(ChatMessageEntity::getConversationId, conversationId)
                                .orderByDesc(ChatMessageEntity::getCreatedAt)
                                .last("LIMIT 1")
                );
                if (last == null || !"user".equalsIgnoreCase(last.getRole())) {
                    throw new BusinessException(400, "没有可重新生成的用户消息");
                }

                applyRequestOptionsToConversation(conversationId, userId, request);

                streamHandle = chatStreamRegistry.register(userId, conversationId);
                bindEmitterCancel(emitter, streamHandle);

                Map<String, Object> meta = new HashMap<>();
                meta.put("conversationId", String.valueOf(conversationId));
                meta.put("streamId", streamHandle.getStreamId());
                meta.put("provider", providerKey);
                meta.put("model", modelId);
                meta.put("regenerate", true);
                emitter.send(SseEmitter.event().name("meta").data(objectMapper.writeValueAsString(meta)));

                ChatConversationEntity convSnap = conversationMapper.selectById(conversationId);
                String aiReply = callAiApiStream(
                        conversationId, providerKey, providerConfig, modelId,
                        emitter, streamHandle, convSnap, request);

                persistAssistantAndComplete(conversationId, userId, null, aiReply, false, emitter);

            } catch (StreamCancelledException e) {
                handleStreamCancelled(conversationId, userId, e, null, emitter);
            } catch (BusinessException e) {
                log.warn("SSE regenerate 业务拒绝: {}", e.getMessage());
                sendSseErrorAndComplete(emitter, e.getMessage() != null ? e.getMessage() : "无权限或请求无效");
            } catch (Exception e) {
                log.error("SSE regenerate 异常", e);
                sendSseErrorAndComplete(emitter, "AI 服务出现异常，请稍后重试。");
            } finally {
                if (streamHandle != null) {
                    chatStreamRegistry.unregister(streamHandle);
                }
                SecurityContextHolder.clearContext();
            }
        });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            sendSseErrorAndComplete(emitter, "对话服务繁忙，请稍后再试");
        }
    }

    /**
     * 编辑用户消息并从此重发（SSE）。
     */
    public void editResendStream(EditResendRequest request, SseEmitter emitter) {
        SecurityContext securityContext = SecurityContextHolder.getContext();
        try {
        streamExecutor.execute(() -> {
            SecurityContextHolder.setContext(securityContext);
            ChatStreamHandle streamHandle = null;
            Long userId = null;
            Long conversationId = null;
            try {
                userId = SecurityUtils.requireCurrentUserId();
                memberStatusService.requireActiveMember(userId);
                conversationId = request.getConversationId();
                requireOwnedConversation(conversationId, userId);

                ChatMessageEntity target = messageMapper.selectById(request.getMessageId());
                if (target == null || !conversationId.equals(target.getConversationId())) {
                    throw new BusinessException(404, "消息不存在");
                }
                if (!"user".equalsIgnoreCase(target.getRole())) {
                    throw new BusinessException(400, "只能编辑用户消息");
                }

                // 删除该消息之后的所有消息
                messageMapper.delete(
                        new LambdaQueryWrapper<ChatMessageEntity>()
                                .eq(ChatMessageEntity::getConversationId, conversationId)
                                .gt(ChatMessageEntity::getCreatedAt, target.getCreatedAt())
                );
                // 同秒多条时用 id 再扫一遍更稳：删除 id 更大的
                messageMapper.delete(
                        new LambdaQueryWrapper<ChatMessageEntity>()
                                .eq(ChatMessageEntity::getConversationId, conversationId)
                                .gt(ChatMessageEntity::getId, target.getId())
                );

                String newText = request.getMessage().trim();
                target.setContent(newText);
                messageMapper.updateById(target);

                ProviderConfig providerConfig = resolveProvider(request.getProvider());
                String modelId = resolveModelId(providerConfig, request.getModel());
                String providerKey = resolveProviderKey(request.getProvider(), providerConfig);
                updateConversationModel(conversationId, userId, request.getProvider(), request.getModel());

                ChatRequest optReq = new ChatRequest();
                optReq.setTemperature(request.getTemperature());
                optReq.setMaxTokens(request.getMaxTokens());
                applyRequestOptionsToConversation(conversationId, userId, optReq);

                streamHandle = chatStreamRegistry.register(userId, conversationId);
                bindEmitterCancel(emitter, streamHandle);

                Map<String, Object> meta = new HashMap<>();
                meta.put("conversationId", String.valueOf(conversationId));
                meta.put("streamId", streamHandle.getStreamId());
                meta.put("provider", providerKey);
                meta.put("model", modelId);
                meta.put("editResend", true);
                meta.put("messageId", String.valueOf(target.getId()));
                emitter.send(SseEmitter.event().name("meta").data(objectMapper.writeValueAsString(meta)));

                ChatConversationEntity convSnap = conversationMapper.selectById(conversationId);
                String aiReply = callAiApiStream(
                        conversationId, providerKey, providerConfig, modelId,
                        emitter, streamHandle, convSnap, optReq);

                persistAssistantAndComplete(conversationId, userId, newText, aiReply, false, emitter);

            } catch (StreamCancelledException e) {
                handleStreamCancelled(conversationId, userId, e, null, emitter);
            } catch (BusinessException e) {
                log.warn("SSE edit-resend 业务拒绝: {}", e.getMessage());
                sendSseErrorAndComplete(emitter, e.getMessage() != null ? e.getMessage() : "无权限或请求无效");
            } catch (Exception e) {
                log.error("SSE edit-resend 异常", e);
                sendSseErrorAndComplete(emitter, "AI 服务出现异常，请稍后重试。");
            } finally {
                if (streamHandle != null) {
                    chatStreamRegistry.unregister(streamHandle);
                }
                SecurityContextHolder.clearContext();
            }
        });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            sendSseErrorAndComplete(emitter, "对话服务繁忙，请稍后再试");
        }
    }

    private void bindEmitterCancel(SseEmitter emitter, ChatStreamHandle handle) {
        // 客户端断开 SSE 时也标记取消，避免后台继续跑完写库
        emitter.onCompletion(() -> {
            if (handle != null) {
                handle.cancel();
            }
        });
        emitter.onTimeout(() -> {
            if (handle != null) {
                handle.cancel();
            }
        });
        emitter.onError(e -> {
            if (handle != null) {
                handle.cancel();
            }
        });
    }

    private void handleStreamCancelled(Long conversationId,
                                       Long userId,
                                       StreamCancelledException e,
                                       String userMessageForTitle,
                                       SseEmitter emitter) {
        try {
            String partial = e.getPartialContent();
            if (conversationId != null && userId != null && partial != null && !partial.isBlank()) {
                persistAssistantAndComplete(conversationId, userId, userMessageForTitle, partial, true, emitter);
            } else {
                Map<String, Object> done = new HashMap<>();
                done.put("cancelled", true);
                done.put("messageId", "");
                emitter.send(SseEmitter.event().name("done").data(objectMapper.writeValueAsString(done)));
                emitter.complete();
            }
        } catch (Exception ex) {
            log.warn("处理取消结果失败: {}", ex.getMessage());
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }

    private void persistAssistantAndComplete(Long conversationId,
                                             Long userId,
                                             String userMessageForTitle,
                                             String aiReply,
                                             boolean cancelled,
                                             SseEmitter emitter) throws Exception {
        String content = aiReply != null ? aiReply : "";
        if (cancelled && !content.isBlank() && !content.contains("已停止生成")) {
            content = content + "\n\n（已停止生成）";
        }
        if (content.isBlank()) {
            Map<String, Object> done = new HashMap<>();
            done.put("cancelled", cancelled);
            done.put("messageId", "");
            emitter.send(SseEmitter.event().name("done").data(objectMapper.writeValueAsString(done)));
            emitter.complete();
            return;
        }

        ChatMessageEntity assistantMsg = new ChatMessageEntity();
        assistantMsg.setConversationId(conversationId);
        assistantMsg.setRole("assistant");
        assistantMsg.setContent(content);
        assistantMsg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(assistantMsg);

        if (userMessageForTitle != null) {
            touchConversationTitle(conversationId, userId, userMessageForTitle);
        } else {
            ChatConversationEntity conv = conversationMapper.selectById(conversationId);
            if (conv != null && conv.getUserId() != null && conv.getUserId().equals(userId)) {
                conv.setUpdatedAt(LocalDateTime.now());
                conversationMapper.updateById(conv);
            }
        }

        Map<String, Object> done = new HashMap<>();
        done.put("messageId", String.valueOf(assistantMsg.getId()));
        done.put("cancelled", cancelled);
        emitter.send(SseEmitter.event().name("done").data(objectMapper.writeValueAsString(done)));
        emitter.complete();
    }

    private void sendSseErrorAndComplete(SseEmitter emitter, String message) {
        try {
            Map<String, Object> error = new HashMap<>();
            error.put("message", message);
            emitter.send(SseEmitter.event().name("error").data(objectMapper.writeValueAsString(error)));
            emitter.complete();
        } catch (Exception ex) {
            try {
                emitter.completeWithError(ex);
            } catch (Exception ignored) {
            }
        }
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

    private static final double DEFAULT_TEMPERATURE = 0.7;
    private static final int DEFAULT_MAX_TOKENS = 2000;

    private void applyRequestOptionsToConversation(Long conversationId, Long userId, ChatRequest request) {
        if (request == null || conversationId == null) {
            return;
        }
        boolean need = request.getTemperature() != null
                || request.getMaxTokens() != null
                || request.getSystemPrompt() != null;
        if (!need) {
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
        if (request.getTemperature() != null) {
            conv.setTemperature(clampTemperature(request.getTemperature()));
        }
        if (request.getMaxTokens() != null) {
            conv.setMaxTokens(clampMaxTokens(request.getMaxTokens()));
        }
        // null = 不改；空串 = 清空回默认；非空 = 写入自定义
        if (request.getSystemPrompt() != null) {
            String sp = request.getSystemPrompt().trim();
            conv.setSystemPrompt(sp.isEmpty() ? null : sp);
        }
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conv);
    }

    private LlmCallOptions chatCallOptions(ChatConversationEntity conv, ChatRequest request) {
        double temp = DEFAULT_TEMPERATURE;
        int maxTok = DEFAULT_MAX_TOKENS;
        if (conv != null && conv.getTemperature() != null) {
            temp = conv.getTemperature();
        }
        if (conv != null && conv.getMaxTokens() != null) {
            maxTok = conv.getMaxTokens();
        }
        if (request != null && request.getTemperature() != null) {
            temp = clampTemperature(request.getTemperature());
        }
        if (request != null && request.getMaxTokens() != null) {
            maxTok = clampMaxTokens(request.getMaxTokens());
        }
        return LlmCallOptions.builder()
                .temperature(temp)
                .maxTokens(maxTok)
                .maxRetries(0)
                .timeoutSeconds(responseTimeoutSeconds())
                .build();
    }

    private static double clampTemperature(double t) {
        return Math.max(0.0, Math.min(2.0, t));
    }

    private static int clampMaxTokens(int m) {
        return Math.max(64, Math.min(16000, m));
    }

    private String resolveSystemPrompt(ChatConversationEntity conv) {
        if (conv != null && conv.getSystemPrompt() != null && !conv.getSystemPrompt().isBlank()) {
            return conv.getSystemPrompt().trim();
        }
        return SYSTEM_PROMPT;
    }

    private String callAiApi(Long conversationId, String providerKey, ProviderConfig provider, String modelId) {
        if (provider == null || provider.getApiKey() == null || provider.getApiKey().isEmpty()) {
            return buildOfflineReply();
        }
        ChatConversationEntity conv = conversationMapper.selectById(conversationId);
        if (aiProperties.isLangChain4jChatEngine()) {
            return callAiApiLangChain4j(conversationId, providerKey, modelId, conv, null);
        }
        return callAiApiOkHttp(conversationId, provider, modelId, conv, null);
    }

    /**
     * 流式调用 AI API，逐步将增量内容通过 SSE 推送给前端。
     * 返回完整的 AI 回复内容（用于持久化）。
     * 若用户取消则抛出 {@link StreamCancelledException}。
     */
    private String callAiApiStream(Long conversationId,
                                   String providerKey,
                                   ProviderConfig provider,
                                   String modelId,
                                   SseEmitter emitter,
                                   ChatStreamHandle streamHandle,
                                   ChatConversationEntity conv,
                                   ChatRequest request) {
        if (provider == null || provider.getApiKey() == null || provider.getApiKey().isEmpty()) {
            String offlineReply = buildOfflineReply();
            sendStreamDelta(emitter, offlineReply);
            return offlineReply;
        }

        if (aiProperties.isLangChain4jChatEngine()) {
            return callAiApiStreamLangChain4j(conversationId, providerKey, modelId, emitter, streamHandle, conv, request);
        }
        return callAiApiStreamOkHttp(conversationId, provider, modelId, emitter, streamHandle, conv, request);
    }

    // ---------- LangChain4j（默认，与三工具共用 ai.chat-engine） ----------

    private String callAiApiLangChain4j(Long conversationId,
                                        String providerKey,
                                        String modelId,
                                        ChatConversationEntity conv,
                                        ChatRequest request) {
        try {
            List<dev.langchain4j.data.message.ChatMessage> messages =
                    buildLangChainMessages(conversationId, resolveSystemPrompt(conv));
            log.info("聊天调用 langchain4j: provider={}, model={}, messages={}",
                    providerKey, modelId, messages.size());
            return llmChatGateway.chatMessages(messages, providerKey, modelId, chatCallOptions(conv, request));
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
                                              SseEmitter emitter,
                                              ChatStreamHandle streamHandle,
                                              ChatConversationEntity conv,
                                              ChatRequest request) {
        StringBuilder fullContent = new StringBuilder();
        try {
            List<dev.langchain4j.data.message.ChatMessage> messages =
                    buildLangChainMessages(conversationId, resolveSystemPrompt(conv));
            log.info("聊天流式调用 langchain4j: provider={}, model={}, messages={}",
                    providerKey, modelId, messages.size());

            String full = llmChatGateway.chatStream(
                    messages,
                    providerKey,
                    modelId,
                    chatCallOptions(conv, request),
                    token -> {
                        if (streamHandle != null && streamHandle.isCancelled()) {
                            throw new StreamCancelledException(fullContent.toString());
                        }
                        fullContent.append(token);
                        sendStreamDelta(emitter, token);
                    },
                    () -> streamHandle != null && streamHandle.isCancelled());
            if (full == null || full.isBlank()) {
                String fallback = "抱歉，AI 未返回有效回复。";
                sendStreamDelta(emitter, fallback);
                return fallback;
            }
            return full;
        } catch (StreamCancelledException e) {
            throw e;
        } catch (Exception e) {
            if (streamHandle != null && streamHandle.isCancelled()) {
                throw new StreamCancelledException(fullContent.toString());
            }
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

    private String callAiApiOkHttp(Long conversationId,
                                   ProviderConfig provider,
                                   String modelId,
                                   ChatConversationEntity conv,
                                   ChatRequest request) {
        try {
            List<Map<String, String>> messages = buildMessagesMap(conversationId, resolveSystemPrompt(conv));
            LlmCallOptions opts = chatCallOptions(conv, request);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelId);
            requestBody.put("messages", messages);
            requestBody.put("temperature", opts.getTemperature());
            requestBody.put("max_tokens", opts.getMaxTokens());

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
                                         SseEmitter emitter,
                                         ChatStreamHandle streamHandle,
                                         ChatConversationEntity conv,
                                         ChatRequest request) {
        try {
            List<Map<String, String>> messages = buildMessagesMap(conversationId, resolveSystemPrompt(conv));
            LlmCallOptions opts = chatCallOptions(conv, request);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelId);
            requestBody.put("messages", messages);
            requestBody.put("temperature", opts.getTemperature());
            requestBody.put("max_tokens", opts.getMaxTokens());
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
                        if (streamHandle != null && streamHandle.isCancelled()) {
                            call.cancel();
                            throw new StreamCancelledException(fullContent.toString());
                        }
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
                        } catch (StreamCancelledException sce) {
                            throw sce;
                        } catch (Exception parseEx) {
                            log.debug("解析流式数据行失败: {}", data, parseEx);
                        }
                    }
                }

                if (streamHandle != null && streamHandle.isCancelled()) {
                    throw new StreamCancelledException(fullContent.toString());
                }

                if (fullContent.isEmpty()) {
                    String fallback = "抱歉，AI 未返回有效回复。";
                    sendStreamDelta(emitter, fallback);
                    return fallback;
                }

                return fullContent.toString();
            } catch (StreamCancelledException e) {
                call.cancel();
                throw e;
            } catch (Exception e) {
                call.cancel();
                if (streamHandle != null && streamHandle.isCancelled()) {
                    throw new StreamCancelledException(fullContent.toString());
                }
                if (isTimeout(e)) {
                    log.warn("AI API 流式超时(okhttp): provider={}, model={}, timeout={}s, partialChars={}",
                            provider.getName(), modelId, responseTimeoutSeconds(), fullContent.length());
                    return handleStreamTimeout(emitter, fullContent);
                }
                throw e;
            }
        } catch (StreamCancelledException e) {
            throw e;
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

    /**
     * Agent 一轮：决策 + 可选 READ 工具 + 推送 tool_result + 推送总结 delta。
     * 决策/工具/总结阶段可响应 stop（与 {@link ChatStreamHandle} 联动）。
     *
     * @return 最终 assistant 文本；null 表示应回退普通流式聊天
     */
    private String runAgentTurnAndStream(String userMessage,
                                         Long conversationId,
                                         Long userId,
                                         String providerKey,
                                         String modelId,
                                         ChatStreamHandle streamHandle,
                                         SseEmitter emitter) {
        if (streamHandle != null && streamHandle.isCancelled()) {
            throw new StreamCancelledException("");
        }
        String historyDigest = buildHistoryDigest(conversationId, 6);
        ToolContext toolCtx = ToolContext.builder()
                .userId(userId)
                .conversationId(conversationId)
                .streamId(streamHandle != null ? streamHandle.getStreamId() : null)
                .build();

        // 推送阶段状态，前端可展示「分析意图 / 执行工具 / 总结」
        sendAgentStatusEvent(emitter, "deciding", "正在分析意图…");

        ChatAgentOrchestrator.AgentTurnResult turn = chatAgentOrchestrator.runTurn(
                userMessage,
                historyDigest,
                providerKey,
                modelId,
                toolCtx,
                () -> streamHandle != null && streamHandle.isCancelled(),
                phase -> {
                    String label = switch (phase != null ? phase : "") {
                        case "deciding" -> "正在分析意图…";
                        case "tool_running" -> "正在调用工具…";
                        case "summarizing" -> "正在整理结果…";
                        default -> "处理中…";
                    };
                    sendAgentStatusEvent(emitter, phase, label);
                });

        if (streamHandle != null && streamHandle.isCancelled()) {
            throw new StreamCancelledException("");
        }

        // 未使用工具且无现成 reply → 外层走正常流式
        if (!turn.isUsedTool() && (turn.getAssistantText() == null || turn.getAssistantText().isBlank())) {
            sendAgentStatusEvent(emitter, "fallback_chat", "改为普通对话…");
            return null;
        }

        if (turn.isUsedTool() && turn.getToolResult() != null) {
            if (turn.isNeedsConfirm()) {
                sendToolConfirmEvent(emitter, turn.getToolName(), turn.getToolResult());
            } else {
                sendToolResultEvent(emitter, turn.getToolName(), turn.getToolResult());
            }
        }

        String text = turn.getAssistantText();
        if (text == null || text.isBlank()) {
            text = turn.getToolResult() != null && turn.getToolResult().getMessage() != null
                    ? turn.getToolResult().getMessage()
                    : "（无回复）";
        }
        // 将确认卡载荷嵌入消息正文，避免前端 loadMessages 后丢失（刷新/重载仍可还原）
        if (turn.isNeedsConfirm() && turn.getToolResult() != null) {
            text = appendConfirmMarker(text, turn.getToolName(), turn.getToolResult());
        } else if (turn.isUsedTool() && turn.getToolResult() != null
                && turn.getToolResult().getUi() != null) {
            text = appendToolResultMarker(text, turn.getToolName(), turn.getToolResult());
        }
        sendAgentStatusEvent(emitter, "done", null);
        sendStreamDelta(emitter, text);
        return text;
    }

    /** SSE：agent 阶段状态（deciding / tool_running / summarizing / done） */
    private void sendAgentStatusEvent(SseEmitter emitter, String phase, String label) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("phase", phase);
            if (label != null) {
                body.put("label", label);
            }
            emitter.send(SseEmitter.event().name("agent_status")
                    .data(objectMapper.writeValueAsString(body)));
        } catch (Exception e) {
            log.debug("发送 agent_status 失败: {}", e.getMessage());
        }
    }

    /**
     * 在消息末尾附加隐藏标记，供前端从历史消息还原工具卡。
     * 格式：\n\n[[AGENT_CONFIRM]]{json}[[/AGENT_CONFIRM]]
     */
    private String appendConfirmMarker(String text, String toolName, ToolResult result) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("tool", toolName);
            body.put("ok", result.isOk());
            body.put("message", result.getMessage());
            body.put("data", result.getData());
            body.put("ui", result.getUi());
            String json = objectMapper.writeValueAsString(body);
            return (text != null ? text : "") + "\n\n[[AGENT_CONFIRM]]" + json + "[[/AGENT_CONFIRM]]";
        } catch (Exception e) {
            return text;
        }
    }

    private String appendToolResultMarker(String text, String toolName, ToolResult result) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("tool", toolName);
            body.put("ok", result.isOk());
            body.put("message", result.getMessage());
            body.put("data", result.getData());
            body.put("ui", result.getUi());
            String json = objectMapper.writeValueAsString(body);
            return (text != null ? text : "") + "\n\n[[AGENT_RESULT]]" + json + "[[/AGENT_RESULT]]";
        } catch (Exception e) {
            return text;
        }
    }

    /**
     * 用户确认写工具草案 → 真正创建任务。
     *
     * @param argOverrides 用户在确认卡上修改的参数，可为 null
     */
    public ToolResult confirmAgentAction(String confirmId, Map<String, Object> argOverrides) {
        memberStatusService.requireActiveMember();
        return agentConfirmService.confirm(confirmId, argOverrides);
    }

    public boolean rejectAgentAction(String confirmId) {
        return agentConfirmService.reject(confirmId);
    }

    private void sendToolConfirmEvent(SseEmitter emitter, String toolName, ToolResult result) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("tool", toolName);
            body.put("ok", result != null && result.isOk());
            body.put("message", result != null ? result.getMessage() : null);
            body.put("data", result != null ? result.getData() : null);
            body.put("ui", result != null ? result.getUi() : null);
            emitter.send(SseEmitter.event().name("tool_confirm")
                    .data(objectMapper.writeValueAsString(body)));
        } catch (Exception e) {
            log.debug("发送 tool_confirm 失败: {}", e.getMessage());
        }
    }

    private void sendToolResultEvent(SseEmitter emitter, String toolName, ToolResult result) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("tool", toolName);
            body.put("ok", result != null && result.isOk());
            body.put("message", result != null ? result.getMessage() : null);
            body.put("data", result != null ? result.getData() : null);
            body.put("ui", result != null ? result.getUi() : null);
            emitter.send(SseEmitter.event().name("tool_result")
                    .data(objectMapper.writeValueAsString(body)));
        } catch (Exception e) {
            log.debug("发送 tool_result 失败: {}", e.getMessage());
        }
    }

    /** 最近若干条消息的短摘要，供意图分类参考 */
    private String buildHistoryDigest(Long conversationId, int limit) {
        List<ChatMessageEntity> history = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getConversationId, conversationId)
                        .orderByDesc(ChatMessageEntity::getCreatedAt)
                        .last("LIMIT " + Math.max(1, limit))
        );
        history.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));
        StringBuilder sb = new StringBuilder();
        for (ChatMessageEntity m : history) {
            String role = m.getRole() != null ? m.getRole() : "?";
            String c = m.getContent() != null ? m.getContent() : "";
            if (c.length() > 120) {
                c = c.substring(0, 120) + "…";
            }
            sb.append(role).append(": ").append(c).append('\n');
        }
        return sb.toString();
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
    private List<dev.langchain4j.data.message.ChatMessage> buildLangChainMessages(Long conversationId,
                                                                                 String systemPrompt) {
        List<ChatMessageEntity> history = loadHistory(conversationId);
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt != null ? systemPrompt : SYSTEM_PROMPT));
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
     * 构建 OkHttp 用 messages：system + 近期历史。
     */
    private List<Map<String, String>> buildMessagesMap(Long conversationId, String systemPrompt) {
        List<ChatMessageEntity> historyMessages = loadHistory(conversationId);
        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> sysMsg = new HashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt != null ? systemPrompt : SYSTEM_PROMPT);
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
