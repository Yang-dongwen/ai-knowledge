package com.dwcode.okxbot.blog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class HaloBindingRequest {

    @NotBlank(message = "站点地址不能为空")
    @Size(max = 256)
    private String baseUrl;

    @Size(max = 256)
    private String publicBaseUrl;

    /** 首次必填；更新时可空表示保留原令牌 */
    @Size(max = 4096)
    private String token;
}
