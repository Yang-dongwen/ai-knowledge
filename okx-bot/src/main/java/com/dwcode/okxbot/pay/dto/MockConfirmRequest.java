package com.dwcode.okxbot.pay.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MockConfirmRequest {

    @NotBlank(message = "orderNo 不能为空")
    private String orderNo;
}
