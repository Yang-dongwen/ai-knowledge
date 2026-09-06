package com.dwcode.okxbot.kb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class FileUploadInitRequest {

    @NotBlank(message = "originalName 不能为空")
    private String originalName;

    @NotNull(message = "sizeBytes 不能为空")
    @Positive(message = "sizeBytes 必须大于 0")
    private Long sizeBytes;

    private String contentType;

    private Long noteId;
}
