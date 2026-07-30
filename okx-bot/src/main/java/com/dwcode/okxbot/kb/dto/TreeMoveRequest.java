package com.dwcode.okxbot.kb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 将文件夹或文档移动到目标文件夹（null=根/未归档）。
 */
@Data
public class TreeMoveRequest {

    /** folder | note */
    @NotBlank
    private String type;

    @NotNull
    private Long id;

    /**
     * 目标文件夹 id；null 表示移到根（文件夹）或未归档（文档）。
     * clearToRoot=true 时强制为 null。
     */
    private Long targetFolderId;

    /** true：移到根 / 未归档 */
    private Boolean clearToRoot;
}
