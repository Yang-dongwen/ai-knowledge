package com.dwcode.okxbot.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dwcode.okxbot.auth.dto.AdminUserPageResponse;
import com.dwcode.okxbot.auth.dto.AuthUserResponse;
import com.dwcode.okxbot.auth.entity.SysUserEntity;
import com.dwcode.okxbot.auth.enums.UserRole;
import com.dwcode.okxbot.auth.mapper.SysUserMapper;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 超级管理员：用户列表 / 启用禁用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final SysUserMapper sysUserMapper;

    public AdminUserPageResponse listUsers(int page, int size, String keyword, String role, Integer status) {
        SecurityUtils.requireSuperAdmin();
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);

        LambdaQueryWrapper<SysUserEntity> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            q.and(w -> w.like(SysUserEntity::getEmail, kw)
                    .or()
                    .like(SysUserEntity::getNickname, kw));
        }
        if (StringUtils.hasText(role)) {
            q.eq(SysUserEntity::getRole, role.trim().toUpperCase());
        }
        if (status != null) {
            q.eq(SysUserEntity::getStatus, status);
        }
        q.orderByDesc(SysUserEntity::getCreatedAt);

        Page<SysUserEntity> mpPage = new Page<>(safePage + 1L, safeSize);
        Page<SysUserEntity> result = sysUserMapper.selectPage(mpPage, q);
        List<AuthUserResponse> items = result.getRecords().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        return AdminUserPageResponse.builder()
                .items(items)
                .total(result.getTotal())
                .page(safePage)
                .size(safeSize)
                .build();
    }

    /**
     * 启用/禁用用户。不能操作自己；不能禁用「最后一个」超级管理员。
     */
    public AuthUserResponse updateStatus(Long userId, int status) {
        SecurityUtils.requireSuperAdmin();
        if (status != 0 && status != 1) {
            throw new BusinessException(400, "status 只能为 0（禁用）或 1（启用）");
        }
        Long selfId = SecurityUtils.requireCurrentUserId();
        if (selfId.equals(userId)) {
            throw new BusinessException(400, "不能禁用或变更自己的账号状态");
        }

        SysUserEntity user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }

        UserRole targetRole = UserRole.from(user.getRole());
        if (status == 0 && targetRole == UserRole.SUPER_ADMIN) {
            Long activeAdmins = sysUserMapper.selectCount(
                    new LambdaQueryWrapper<SysUserEntity>()
                            .eq(SysUserEntity::getRole, UserRole.SUPER_ADMIN.name())
                            .eq(SysUserEntity::getStatus, 1)
            );
            if (activeAdmins != null && activeAdmins <= 1) {
                throw new BusinessException(400, "不能禁用最后一个启用中的超级管理员");
            }
        }

        user.setStatus(status);
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
        log.info("管理员变更用户状态: operatorId={}, targetId={}, email={}, status={}",
                selfId, userId, user.getEmail(), status);
        return toResponse(user);
    }

    private AuthUserResponse toResponse(SysUserEntity user) {
        UserRole role = UserRole.from(user.getRole());
        return AuthUserResponse.builder()
                .id(String.valueOf(user.getId()))
                .email(user.getEmail())
                .nickname(user.getNickname())
                .role(role.name())
                .roleLabel(role.displayName())
                .emailVerified(user.getEmailVerified() != null && user.getEmailVerified() == 1)
                .status(user.getStatus())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
