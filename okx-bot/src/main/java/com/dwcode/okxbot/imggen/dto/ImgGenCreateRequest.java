package com.dwcode.okxbot.imggen.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ImgGenCreateRequest {
    @NotBlank(message = "prompt 不能为空")
    @Size(max = 4000, message = "prompt 最长 4000 字符")
    private String prompt;

    @Size(max = 1024, message = "negativePrompt 最长 1024 字符")
    private String negativePrompt;

    @Valid
    private ImgGenCreateOptions options;
}
