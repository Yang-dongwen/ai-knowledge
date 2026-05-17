package com.dwcode.okxbot.okx.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * OKX 配置查询响应。
 */
@Data
public class OkxConfigResponse {

    private String apiKeyMasked;
    private Integer simulated;
    private String status;
    private LocalDateTime lastCheckAt;
    private String lastError;
}
