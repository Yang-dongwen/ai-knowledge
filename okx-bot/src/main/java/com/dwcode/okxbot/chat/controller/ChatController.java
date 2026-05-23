package com.dwcode.okxbot.chat.controller;

import com.dwcode.okxbot.chat.dto.ChatRequest;
import com.dwcode.okxbot.chat.entity.ChatConversationEntity;
import com.dwcode.okxbot.chat.service.ChatService;
import com.dwcode.okxbot.common.response.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI 聊天接口。
 */
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
     * 发送消息。
     */
    @PostMapping("/send")
    public ApiResult<Map<String, Object>> sendMessage(@Valid @RequestBody ChatRequest request) {
        return ApiResult.ok(chatService.sendMessage(request));
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