package com.dwcode.okxbot.pay.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreatePayOrderRequest {

    @NotBlank(message = "planId 不能为空")
    private String planId;

    /** alipay / wechat / mock */
    @NotBlank(message = "channel 不能为空")
    private String channel;

    /** PC / H5 */
    private String clientType = "PC";
}
