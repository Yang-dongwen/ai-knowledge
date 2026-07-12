package com.dwcode.okxbot.chat.controller;

import com.dwcode.okxbot.chat.dto.ChatRequest;
import com.dwcode.okxbot.chat.entity.ChatConversationEntity;
import com.dwcode.okxbot.chat.service.ChatService;
import com.dwcode.okxbot.common.response.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
     */
    @GetMapping("/conversations")
    public ApiResult<List<ChatConversationEntity>> listConversations() {
        return ApiResult.ok(chatService.listConversations());
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
        // 超时设置为 3 分钟
        SseEmitter emitter = new SseEmitter(180_000L);

        emitter.onCompletion(() -> log.debug("SSE 连接完成"));
        emitter.onTimeout(() -> log.warn("SSE 连接超时"));
        emitter.onError(e -> log.error("SSE 连接异常", e));

        // 异步执行流式调用
        chatService.sendMessageStream(request, emitter);

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