package com.dwcode.okxbot.auth.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 当前用户公开信息（不含密码）。
 */
@Data
@Builder
public class AuthUserResponse {
    private String id;
    private String email;
    private String nickname;
    /** USER / MEMBER / SUPER_ADMIN */
    private String role;
    /** 角色中文名 */
    private String roleLabel;
    private Boolean emailVerified;
    /** 1 正常 0 禁用 */
    private Integer status;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastLoginAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
