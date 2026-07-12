package com.dwcode.okxbot.auth.controller;

import com.dwcode.okxbot.auth.dto.*;
import com.dwcode.okxbot.auth.service.AuthService;
import com.dwcode.okxbot.common.response.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 认证接口：注册 / 登录 / 找回密码。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register/send-code")
    public ApiResult<Void> sendRegisterCode(@Valid @RequestBody SendCodeRequest request) {
        authService.sendRegisterCode(request.getEmail());
        return ApiResult.ok();
    }

    @PostMapping("/register")
    public ApiResult<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResult.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResult<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResult.ok(authService.login(request));
    }

    @PostMapping("/password/send-code")
    public ApiResult<Void> sendResetCode(@Valid @RequestBody SendCodeRequest request) {
        authService.sendResetCode(request.getEmail());
        // 统一成功，防邮箱枚举
        return ApiResult.ok();
    }

    @PostMapping("/password/reset")
    public ApiResult<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ApiResult.ok();
    }

    @GetMapping("/me")
    public ApiResult<AuthUserResponse> me() {
        return ApiResult.ok(authService.me());
    }

    @PostMapping("/logout")
    public ApiResult<Void> logout() {
        // JWT 无状态：客户端删除 token 即可
        return ApiResult.ok();
    }
}
