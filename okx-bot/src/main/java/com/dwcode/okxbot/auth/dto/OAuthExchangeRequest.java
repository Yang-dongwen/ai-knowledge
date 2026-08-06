package com.dwcode.okxbot.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OAuthExchangeRequest {

    @NotBlank(message = "ticket 不能为空")
    private String ticket;
}
