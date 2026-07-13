package com.dwcode.okxbot.imggen.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ImgGenCreateRequest {
    @NotBlank(message = "prompt 不能为空")
    private String prompt;
    private String negativePrompt;
    private ImgGenCreateOptions options;
}
