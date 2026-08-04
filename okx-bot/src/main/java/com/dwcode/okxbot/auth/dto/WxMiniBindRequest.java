package com.dwcode.okxbot.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WxMiniBindRequest {

    @NotBlank(message = "code 不能为空")
    private String code;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    @NotBlank(message = "密码不能为空")
    @Size(min = 1, max = 72, message = "密码长度不合法")
    private String password;
}
