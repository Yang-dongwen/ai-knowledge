package com.dwcode.okxbot.auth.security;

import com.dwcode.okxbot.auth.enums.UserRole;
import com.dwcode.okxbot.common.exception.BusinessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.function.Supplier;

/**
 * 当前登录用户工具。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static AuthUserPrincipal requireCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthUserPrincipal principal)) {
            throw new BusinessException(401, "未登录或登录已过期");
        }
        return principal;
    }

    public static Long requireCurrentUserId() {
        return requireCurrentUser().getId();
    }

    public static AuthUserPrincipal currentUserOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUserPrincipal principal) {
            return principal;
        }
        return null;
    }

    public static UserRole currentRole() {
        AuthUserPrincipal p = currentUserOrNull();
        return p == null ? UserRole.USER : p.getRole();
    }

    public static boolean isSuperAdmin() {
        AuthUserPrincipal p = currentUserOrNull();
        return p != null && p.isSuperAdmin();
    }

    public static void requireSuperAdmin() {
        if (!isSuperAdmin()) {
            throw new BusinessException(403, "需要超级管理员权限");
        }
    }

    /**
     * 在给定用户身份下执行（webhook 等无 JWT 的入口用完即恢复）。
     */
    public static <T> T runAs(AuthUserPrincipal principal, Supplier<T> action) {
        if (principal == null) {
            throw new BusinessException(401, "未登录或登录已过期");
        }
        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                    principal, null, principal.getAuthorities()));
            SecurityContextHolder.setContext(ctx);
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
