package com.dwcode.okxbot.chat.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 对话会话实体（按用户隔离空间）。
 */
@Data
@TableName("chat_conversation")
public class ChatConversationEntity {

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属用户 ID（多租户隔离） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String title;

    /** 供应商标识，如 deepseek、openai */
    private String provider;

    /** 模型ID，如 deepseek-chat */
    private String model;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}