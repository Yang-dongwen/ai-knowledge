package com.dwcode.okxbot.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AgentRejectRequest {

    @NotBlank(message = "confirmId 不能为空")
    private String confirmId;
}
