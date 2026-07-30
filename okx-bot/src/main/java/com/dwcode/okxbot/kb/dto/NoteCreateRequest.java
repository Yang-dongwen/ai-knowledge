package com.dwcode.okxbot.kb.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class NoteCreateRequest {

    @Size(max = 200, message = "标题不能超过200字")
    private String title;

    private String content;

    /** html | markdown，默认配置项 */
    private String contentFormat;

    private Long categoryId;

    private List<Long> tagIds;

    /** 可选，默认 false */
    private Boolean pinned;
}
