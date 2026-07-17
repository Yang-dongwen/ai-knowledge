package com.dwcode.okxbot.member.controller;

import com.dwcode.okxbot.auth.entity.SysUserEntity;
import com.dwcode.okxbot.auth.enums.UserRole;
import com.dwcode.okxbot.auth.mapper.SysUserMapper;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.member.dto.MemberPlanResponse;
import com.dwcode.okxbot.member.dto.MemberStatusResponse;
import com.dwcode.okxbot.member.service.MemberPlanService;
import com.dwcode.okxbot.member.service.MemberStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/member")
@RequiredArgsConstructor
public class MemberController {

    private final MemberPlanService memberPlanService;
    private final MemberStatusService memberStatusService;
    private final SysUserMapper sysUserMapper;

    @GetMapping("/plans")
    public ApiResult<List<MemberPlanResponse>> plans() {
        SecurityUtils.requireCurrentUserId();
        return ApiResult.ok(memberPlanService.listOnSale());
    }

    @GetMapping("/status")
    public ApiResult<MemberStatusResponse> status() {
        Long userId = SecurityUtils.requireCurrentUserId();
        memberStatusService.demoteIfExpired(userId);
        SysUserEntity user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(401, "用户不存在或已删除");
        }
        UserRole role = UserRole.from(user.getRole());
        return ApiResult.ok(MemberStatusResponse.builder()
                .role(role.name())
                .roleLabel(role.displayName())
                .memberActive(memberStatusService.isActive(user))
                .memberExpireAt(user.getMemberExpireAt())
                .build());
    }
}
