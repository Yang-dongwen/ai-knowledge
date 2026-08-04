package com.dwcode.okxbot.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WxMiniCodeRequest {

    /** wx.login 得到的 code；mock 模式下可直接传 openid 或 mock:xxx */
    @NotBlank(message = "code 不能为空")
    private String code;
}
