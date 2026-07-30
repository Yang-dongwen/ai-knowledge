package com.dwcode.okxbot.kb.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class NoteBatchMoveRequest {

    @NotEmpty
    private List<Long> noteIds;

    /** 目标文件夹；null + clearToRoot 表示未归档 */
    private Long targetFolderId;

    private Boolean clearToRoot;
}
