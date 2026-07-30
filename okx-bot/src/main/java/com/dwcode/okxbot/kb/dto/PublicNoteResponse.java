package com.dwcode.okxbot.kb.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 公开分享页：仅暴露阅读所需字段，不含 userId。
 */
@Data
@Builder
public class PublicNoteResponse {

    private String title;
    private String content;
    /** html | markdown */
    private String contentFormat;
    private String authorName;
    private LocalDateTime updatedAt;
    private LocalDateTime publishedAt;
    private List<String> tags;
}
