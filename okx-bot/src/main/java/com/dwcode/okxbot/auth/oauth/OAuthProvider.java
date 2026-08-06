package com.dwcode.okxbot.auth.oauth;

import com.dwcode.okxbot.common.exception.BusinessException;

/**
 * PC 端支持的 OAuth 提供方。
 */
public enum OAuthProvider {
    GOOGLE,
    GITHUB;

    public static OAuthProvider fromPath(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(400, "未知的登录方式");
        }
        try {
            return OAuthProvider.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "不支持的登录方式: " + raw);
        }
    }

    public String path() {
        return name().toLowerCase();
    }
}
