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

    /**
     * 是否写入版本快照。自动保存应 false；手动保存 / 离开文档为 true。
     * 即便为 false，距上次快照超过间隔时仍会打一个检查点。
     */
    private Boolean createRevision;
}
