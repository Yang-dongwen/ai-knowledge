package com.dwcode.okxbot.kb.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class NoteRevisionResponse {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long noteId;

    private String title;
    /** 列表不返回全文；详情/恢复时可选 */
    private String content;
    private String contentFormat;
    private String source;
    private String snippet;
    private LocalDateTime createdAt;
}
