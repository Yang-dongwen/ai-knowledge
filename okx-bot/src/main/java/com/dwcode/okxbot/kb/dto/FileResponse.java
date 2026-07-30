package com.dwcode.okxbot.kb.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class FileResponse {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long noteId;

    private String originalName;
    private String contentType;
    private long sizeBytes;
    private String kind;
    /** 同源可访问路径（需带 JWT 或 access_token） */
    private String contentPath;
    private LocalDateTime createdAt;
}
