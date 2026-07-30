package com.dwcode.okxbot.kb.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CategoryUpdateRequest {

    @Size(max = 64, message = "分类名称不能超过64字")
    private String name;

    private Long parentId;

    /** true 时将 parent 置空（移到根） */
    private Boolean clearParent;

    private Integer sortOrder;
}
