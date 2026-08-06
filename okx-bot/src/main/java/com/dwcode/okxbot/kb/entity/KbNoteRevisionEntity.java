package com.dwcode.okxbot.kb.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库笔记版本快照。
 */
@Data
@TableName("kb_note_revision")
public class KbNoteRevisionEntity {

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long noteId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String title;
    private String content;
    private String contentFormat;
    /** save | restore | manual */
    private String source;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
