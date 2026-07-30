package com.dwcode.okxbot.kb.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class NoteUpdateRequest {

    @Size(max = 200, message = "标题不能超过200字")
    private String title;

    private String content;

    /** html | markdown */
    private String contentFormat;

    private Long categoryId;

    /** 是否清空分类（categoryId 为 null 时：false=不改分类，true=置空） */
    private Boolean clearCategory;

    private List<Long> tagIds;

    private Boolean pinned;
}
