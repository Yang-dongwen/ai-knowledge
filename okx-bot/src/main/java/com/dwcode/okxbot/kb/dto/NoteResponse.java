package com.dwcode.okxbot.kb.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class NoteResponse {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String title;

    /** 详情返回全文；列表可为空 */
    private String content;

    /** html | markdown */
    private String contentFormat;

    /** 列表摘要 */
    private String snippet;

    /** 搜索命中片段（含上下文；无 keyword 时为空） */
    private String matchSnippet;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long categoryId;

    private String categoryName;

    private List<TagBrief> tags;

    private boolean pinned;

    private boolean deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
