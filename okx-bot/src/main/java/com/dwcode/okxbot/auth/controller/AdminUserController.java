package com.dwcode.okxbot.auth.controller;

import com.dwcode.okxbot.auth.dto.AdminUserPageResponse;
import com.dwcode.okxbot.auth.dto.AuthUserResponse;
import com.dwcode.okxbot.auth.dto.UpdateUserStatusRequest;
import com.dwcode.okxbot.auth.service.AdminUserService;
import com.dwcode.okxbot.common.response.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 超级管理员：用户管理。
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    /**
     * 分页查询用户列表。
     *
     * @param page    从 0 开始
     * @param size    每页条数
     * @param keyword 邮箱/昵称模糊
     * @param role    USER / MEMBER / SUPER_ADMIN
     * @param status  1 正常 0 禁用
     */
    @GetMapping
    public ApiResult<AdminUserPageResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Integer status) {
        return ApiResult.ok(adminUserService.listUsers(page, size, keyword, role, status));
    }

    /**
     * 启用/禁用账号。body: { "status": 0|1 }
     */
    @PutMapping("/{id}/status")
    public ApiResult<AuthUserResponse> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserStatusRequest request) {
        return ApiResult.ok(adminUserService.updateStatus(id, request.getStatus()));
    }
}
