package com.dwcode.okxbot.auth.security;

import com.dwcode.okxbot.auth.entity.SysUserEntity;
import com.dwcode.okxbot.auth.enums.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SecurityUtilsRunAsTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void runAsSetsAndRestores() {
        assertNull(SecurityUtils.currentUserOrNull());
        SysUserEntity u = new SysUserEntity();
        u.setId(9L);
        u.setEmail("a@b.c");
        u.setPasswordHash("x");
        u.setRole(UserRole.SUPER_ADMIN.name());
        u.setStatus(1);
        u.setTokenVersion(0);
        AuthUserPrincipal p = new AuthUserPrincipal(u);

        Long id = SecurityUtils.runAs(p, SecurityUtils::requireCurrentUserId);
        assertEquals(9L, id);
        assertNull(SecurityUtils.currentUserOrNull());
    }
}
