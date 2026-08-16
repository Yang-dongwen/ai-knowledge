package com.dwcode.okxbot.blog;

import com.dwcode.okxbot.auth.entity.SysUserEntity;
import com.dwcode.okxbot.auth.security.AuthUserPrincipal;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.blog.config.HaloProperties;
import com.dwcode.okxbot.blog.entity.UserHaloBindingEntity;
import com.dwcode.okxbot.blog.mapper.UserHaloBindingMapper;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class HaloClientResolverTest {

    @Test
    void regularUserWithoutBindingForbidden() {
        HaloProperties p = new HaloProperties();
        p.setEnabled(true);
        p.setToken("pat_platform");
        p.setBaseUrl("https://blog.dwcode.cloud");
        UserHaloBindingMapper mapper = mock(UserHaloBindingMapper.class);
        when(mapper.selectById(7L)).thenReturn(null);
        HaloClientResolver resolver = new HaloClientResolver(
                p, mapper, new HaloTokenCipher("s"), new ObjectMapper());
        try (MockedStatic<SecurityUtils> st = mockStatic(SecurityUtils.class)) {
            st.when(SecurityUtils::requireCurrentUser).thenReturn(principal(7L, "USER"));
            BusinessException ex = assertThrows(BusinessException.class, resolver::resolve);
            assertEquals(403, ex.getCode());
            assertTrue(ex.getMessage().contains("关联"));
        }
    }

    @Test
    void regularUserDoesNotUsePlatformToken() {
        HaloProperties p = new HaloProperties();
        p.setEnabled(true);
        p.setToken("pat_platform");
        p.setBaseUrl("https://blog.dwcode.cloud");
        p.setPublicBaseUrl("https://blog.dwcode.cloud");
        HaloTokenCipher cipher = new HaloTokenCipher("s");
        UserHaloBindingEntity b = new UserHaloBindingEntity();
        b.setUserId(7L);
        b.setBaseUrl("https://mine.example");
        b.setPublicBaseUrl("https://mine.example");
        b.setTokenCipher(cipher.encrypt("pat_mine"));
        UserHaloBindingMapper mapper = mock(UserHaloBindingMapper.class);
        when(mapper.selectById(7L)).thenReturn(b);
        HaloClientResolver resolver = new HaloClientResolver(p, mapper, cipher, new ObjectMapper());
        try (MockedStatic<SecurityUtils> st = mockStatic(SecurityUtils.class)) {
            st.when(SecurityUtils::requireCurrentUser).thenReturn(principal(7L, "USER"));
            HaloClientResolver.Resolved r = resolver.resolve();
            assertEquals(HaloClientResolver.TARGET_PERSONAL, r.target());
            assertEquals("https://mine.example", r.publicBaseUrl());
        }
    }

    @Test
    void superAdminUsesPlatform() {
        HaloProperties p = new HaloProperties();
        p.setEnabled(true);
        p.setToken("pat_platform");
        p.setBaseUrl("https://blog.dwcode.cloud");
        p.setPublicBaseUrl("https://blog.dwcode.cloud");
        HaloClientResolver resolver = new HaloClientResolver(
                p, mock(UserHaloBindingMapper.class), new HaloTokenCipher("s"), new ObjectMapper());
        try (MockedStatic<SecurityUtils> st = mockStatic(SecurityUtils.class)) {
            st.when(SecurityUtils::requireCurrentUser).thenReturn(principal(1L, "SUPER_ADMIN"));
            HaloClientResolver.Resolved r = resolver.resolve();
            assertEquals(HaloClientResolver.TARGET_PLATFORM, r.target());
        }
    }

    @Test
    void sameSiteMatch() {
        HaloClientResolver resolver = new HaloClientResolver(
                new HaloProperties(), mock(UserHaloBindingMapper.class),
                new HaloTokenCipher("s"), new ObjectMapper());
        assertTrue(resolver.sameSite("https://mine.example/archives/a", "https://mine.example"));
    }

    private static AuthUserPrincipal principal(Long id, String role) {
        SysUserEntity u = new SysUserEntity();
        u.setId(id);
        u.setEmail("u" + id + "@ex.com");
        u.setPasswordHash("x");
        u.setRole(role);
        u.setStatus(1);
        u.setTokenVersion(0);
        return new AuthUserPrincipal(u);
    }
}
