package com.dwcode.okxbot.auth.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateUserStatusRequest {
    /** 1 正常 0 禁用 */
    @NotNull(message = "status 不能为空")
    @Min(value = 0, message = "status 只能为 0 或 1")
    @Max(value = 1, message = "status 只能为 0 或 1")
    private Integer status;
}
