package com.dwcode.okxbot.kb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 同级重排：orderedIds 从上到下顺序写入 sort_order = 0,1,2...
 */
@Data
public class TreeReorderRequest {

    /** folder | note */
    @NotBlank
    private String type;

    /**
     * 父文件夹 id；null=根层文件夹 / 未归档文档。
     * clearParent=true 时视为 null。
     */
    private Long parentFolderId;

    private Boolean clearParent;

    @NotEmpty
    private List<Long> orderedIds;
}
