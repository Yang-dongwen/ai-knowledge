package com.dwcode.okxbot.auth.enums;

/**
 * 用户角色等级（权限由低到高）：
 * USER 普通用户 → MEMBER 会员 → SUPER_ADMIN 超级管理员
 */
public enum UserRole {
    /** 普通用户（默认注册） */
    USER,
    /** 会员（充值后，功能后期扩展） */
    MEMBER,
    /** 超级管理员（交易管理、模型管理等） */
    SUPER_ADMIN;

    public static UserRole from(String raw) {
        if (raw == null || raw.isBlank()) {
            return USER;
        }
        try {
            return UserRole.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return USER;
        }
    }

    public String displayName() {
        return switch (this) {
            case USER -> "普通用户";
            case MEMBER -> "会员";
            case SUPER_ADMIN -> "超级管理员";
        };
    }

    public boolean isSuperAdmin() {
        return this == SUPER_ADMIN;
    }
}
