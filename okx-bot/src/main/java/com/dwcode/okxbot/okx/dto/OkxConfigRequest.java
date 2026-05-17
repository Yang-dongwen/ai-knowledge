package com.dwcode.okxbot.okx.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * OKX 配置保存请求。
 */
@Data
public class OkxConfigRequest {

    @NotBlank(message = "API Key不能为空")
    private String apiKey;

    @NotBlank(message = "Secret Key不能为空")
    private String secretKey;

    @NotBlank(message = "Passphrase不能为空")
    private String passphrase;

    @NotNull(message = "是否模拟盘不能为空")
    private Integer simulated;
}
