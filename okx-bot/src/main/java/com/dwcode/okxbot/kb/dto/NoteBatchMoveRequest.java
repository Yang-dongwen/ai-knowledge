package com.dwcode.okxbot.kb.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class NoteBatchMoveRequest {

    @NotEmpty
    @Size(max = 200, message = "单次批量移动不能超过200条")
    private List<Long> noteIds;

    /** 目标文件夹；null + clearToRoot 表示未归档 */
    private Long targetFolderId;

    private Boolean clearToRoot;
}
