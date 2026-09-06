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

@Data
@TableName("kb_file_upload")
public class KbFileUploadEntity {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_COMPLETING = "completing";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_ABORTED = "aborted";

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long noteId;

    private String originalName;
    private String contentType;
    private String kind;
    private Long totalBytes;
    private Integer chunkSize;
    private Integer totalParts;
    private String status;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long fileId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private LocalDateTime expiresAt;
}
