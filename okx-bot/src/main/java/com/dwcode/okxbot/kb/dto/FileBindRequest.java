package com.dwcode.okxbot.kb.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FileBindRequest {
    @NotNull(message = "noteId 不能为空")
    private Long noteId;
}
