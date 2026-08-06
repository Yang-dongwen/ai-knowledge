package com.dwcode.okxbot.auth.oauth;

import com.dwcode.okxbot.auth.config.AuthProperties;
import com.dwcode.okxbot.auth.mapper.SysUserMapper;
import com.dwcode.okxbot.auth.oauth.mapper.UserOAuthBindingMapper;
import com.dwcode.okxbot.auth.service.AuthService;
import com.dwcode.okxbot.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class OAuthServiceSanitizeTest {

    private OAuthService service;

    @BeforeEach
    void setUp() {
        AuthProperties props = new AuthProperties();
        props.getOauth().setFrontendBaseUrl("http://localhost:3000");
        props.getOauth().setCallbackBaseUrl("http://localhost:8080");
        props.getOauth().setMock(true);
        service = new OAuthService(
                props,
                mock(OAuthTokenStore.class),
                mock(JustAuthClient.class),
                mock(UserOAuthBindingMapper.class),
                mock(SysUserMapper.class),
                mock(AuthService.class)
        );
    }

    @Test
    void defaultRedirectHome() {
        assertEquals("/home", service.sanitizeRedirect(null));
        assertEquals("/home", service.sanitizeRedirect("  "));
    }

    @Test
    void acceptsRelativePath() {
        assertEquals("/video-extract", service.sanitizeRedirect("/video-extract"));
        assertEquals("/home?x=1", service.sanitizeRedirect("/home?x=1"));
    }

    @Test
    void rejectsOpenRedirect() {
        assertThrows(BusinessException.class, () -> service.sanitizeRedirect("https://evil.com"));
        assertThrows(BusinessException.class, () -> service.sanitizeRedirect("//evil.com"));
        assertThrows(BusinessException.class, () -> service.sanitizeRedirect("video-extract"));
    }
}
