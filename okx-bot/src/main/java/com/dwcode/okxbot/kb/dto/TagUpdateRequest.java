package com.dwcode.okxbot.kb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TagUpdateRequest {

    @NotBlank(message = "标签名称不能为空")
    @Size(max = 64, message = "标签名称不能超过64字")
    private String name;
}
