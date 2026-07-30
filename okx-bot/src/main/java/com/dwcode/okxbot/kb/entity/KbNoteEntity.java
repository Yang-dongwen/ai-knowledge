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
 * 知识库笔记。
 */
@Data
@TableName("kb_note")
public class KbNoteEntity {

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String title;
    private String content;
    /** html | markdown */
    private String contentFormat;
    /** 列表摘要（纯文本，保存时维护；list 接口不加载 content） */
    private String snippet;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long categoryId;

    /** 0/1 */
    private Integer isPinned;
    /** 0/1 软删除（非 MP 全局 logic-delete 字段，业务自管） */
    private Integer isDeleted;
    private LocalDateTime deletedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
