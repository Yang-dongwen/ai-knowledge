package com.dwcode.okxbot.chat.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天消息 DTO，返回给前端。
 */
@Data
public class ChatMessageDTO {

    private String id;
    private String role;
    private String content;
    private LocalDateTime timestamp;
}