package com.dwcode.okxbot.chat.controller;

import com.dwcode.okxbot.chat.agent.ToolResult;
import com.dwcode.okxbot.chat.dto.AgentConfirmRequest;
import com.dwcode.okxbot.chat.dto.AgentRejectRequest;
import com.dwcode.okxbot.chat.dto.ChatRequest;
import com.dwcode.okxbot.chat.dto.EditResendRequest;
import com.dwcode.okxbot.chat.dto.RenameConversationRequest;
import com.dwcode.okxbot.chat.dto.StopChatRequest;
import com.dwcode.okxbot.chat.dto.UpdateConversationRequest;
import com.dwcode.okxbot.chat.entity.ChatConversationEntity;
import com.dwcode.okxbot.chat.service.ChatService;
import com.dwcode.okxbot.common.response.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 聊天接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * 获取可用模型列表（apiKey 不为空的供应商）。
     */
    @GetMapping("/models")
    public ApiResult<List<Map<String, Object>>> listModels() {
        return ApiResult.ok(chatService.listAvailableModels());
    }

    /**
     * 获取会话列表。
     *
     * @param keyword 可选，按标题模糊搜索
     */
    @GetMapping("/conversations")
    public ApiResult<List<ChatConversationEntity>> listConversations(
            @RequestParam(required = false) String keyword) {
        return ApiResult.ok(chatService.listConversations(keyword));
    }

    /**
     * 重命名会话（兼容旧客户端）。
     */
    @PatchMapping("/conversations/{conversationId}")
    public ApiResult<ChatConversationEntity> renameConversation(
            @PathVariable Long conversationId,
            @Valid @RequestBody RenameConversationRequest request) {
        return ApiResult.ok(chatService.renameConversation(conversationId, request.getTitle()));
    }

    /**
     * 更新会话：标题 / 模型 / temperature / maxTokens / systemPrompt。
     */
    @PutMapping("/conversations/{conversationId}")
    public ApiResult<ChatConversationEntity> updateConversation(
            @PathVariable Long conversationId,
            @Valid @RequestBody UpdateConversationRequest request) {
        return ApiResult.ok(chatService.updateConversation(conversationId, request));
    }

    /**
     * 获取会话消息列表。
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public ApiResult<Object> getMessages(@PathVariable Long conversationId) {
        return ApiResult.ok(chatService.getMessages(conversationId));
    }

    /**
     * 发送消息（SSE 流式响应）。
     *
     * SSE 事件格式：
     * - event: meta, data: {"conversationId":"xxx"} — 会话元信息
     * - event: delta, data: {"content":"xxx"} — AI 回复增量内容
     * - event: done, data: {"messageId":"xxx"} — 流式结束
     * - event: error, data: {"message":"xxx"} — 错误信息
     */
    @PostMapping(value = "/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(@Valid @RequestBody ChatRequest request) {
        // 长回复兜底；空闲无输出由业务层 idle timeout 控制
        SseEmitter emitter = new SseEmitter(30 * 60_000L);

        emitter.onCompletion(() -> log.debug("SSE 连接完成"));
        emitter.onTimeout(() -> log.warn("SSE 连接超时"));
        emitter.onError(e -> log.error("SSE 连接异常", e));

        chatService.sendMessageStream(request, emitter);

        return emitter;
    }

    /**
     * 重新生成最后一条 AI 回复（SSE）。
     * 删除会话末尾 assistant 消息后，基于已有 user 消息再次流式生成。
     */
    @PostMapping(value = "/regenerate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter regenerate(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(30 * 60_000L);

        emitter.onCompletion(() -> log.debug("SSE regenerate 完成"));
        emitter.onTimeout(() -> log.warn("SSE regenerate 超时"));
        emitter.onError(e -> log.error("SSE regenerate 异常", e));

        chatService.regenerateStream(request, emitter);

        return emitter;
    }

    /**
     * 停止当前流式生成（真取消后端推理与后续写库完整内容，仅保留已推送部分）。
     * body: { "streamId": "...", "conversationId": 123 } 至少其一。
     */
    @PostMapping("/stop")
    public ApiResult<Map<String, Object>> stop(@RequestBody(required = false) StopChatRequest request) {
        StopChatRequest body = request != null ? request : new StopChatRequest();
        boolean ok = chatService.stopStream(body.getStreamId(), body.getConversationId());
        Map<String, Object> data = new HashMap<>();
        data.put("stopped", ok);
        return ApiResult.ok(data);
    }

    /**
     * 确认 Agent 写工具草案（真正创建任务）。
     */
    @PostMapping("/agent/confirm")
    public ApiResult<ToolResult> agentConfirm(@Valid @RequestBody AgentConfirmRequest request) {
        return ApiResult.ok(chatService.confirmAgentAction(request.getConfirmId(), request.getArgs()));
    }

    /**
     * 拒绝 Agent 写工具草案。
     */
    @PostMapping("/agent/reject")
    public ApiResult<Map<String, Object>> agentReject(@Valid @RequestBody AgentRejectRequest request) {
        boolean ok = chatService.rejectAgentAction(request.getConfirmId());
        Map<String, Object> data = new HashMap<>();
        data.put("rejected", ok);
        return ApiResult.ok(data);
    }

    /**
     * 编辑用户消息并从此重发（SSE）：截断后续消息，更新内容后重新流式生成。
     */
    @PostMapping(value = "/edit-resend", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter editResend(@Valid @RequestBody EditResendRequest request) {
        SseEmitter emitter = new SseEmitter(30 * 60_000L);
        emitter.onCompletion(() -> log.debug("SSE edit-resend 完成"));
        emitter.onTimeout(() -> log.warn("SSE edit-resend 超时"));
        emitter.onError(e -> log.error("SSE edit-resend 异常", e));
        chatService.editResendStream(request, emitter);
        return emitter;
    }

    /**
     * 删除会话。
     */
    @DeleteMapping("/conversations/{conversationId}")
    public ApiResult<Void> deleteConversation(@PathVariable Long conversationId) {
        chatService.deleteConversation(conversationId);
        return ApiResult.ok();
    }
}