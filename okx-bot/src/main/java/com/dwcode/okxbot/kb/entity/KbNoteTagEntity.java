package com.dwcode.okxbot.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 笔记-标签关联（复合主键；insert/delete 走 Wrapper 或显式字段）。
 */
@Data
@TableName("kb_note_tag")
public class KbNoteTagEntity {

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(value = "note_id", type = IdType.INPUT)
    private Long noteId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tagId;
}
