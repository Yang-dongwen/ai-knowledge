package com.dwcode.okxbot.auth.oauth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.auth.config.AuthProperties;
import com.dwcode.okxbot.auth.entity.SysUserEntity;
import com.dwcode.okxbot.auth.mapper.SysUserMapper;
import com.dwcode.okxbot.auth.oauth.entity.UserOAuthBindingEntity;
import com.dwcode.okxbot.auth.oauth.mapper.UserOAuthBindingMapper;
import com.dwcode.okxbot.auth.service.AuthService;
import com.dwcode.okxbot.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OAuthFindOrCreateTest {

    private UserOAuthBindingMapper bindingMapper;
    private SysUserMapper sysUserMapper;
    private OAuthService service;

    @BeforeEach
    void setUp() {
        AuthProperties props = new AuthProperties();
        bindingMapper = mock(UserOAuthBindingMapper.class);
        sysUserMapper = mock(SysUserMapper.class);
        service = new OAuthService(
                props,
                mock(OAuthTokenStore.class),
                mock(JustAuthClient.class),
                bindingMapper,
                sysUserMapper,
                mock(AuthService.class)
        );
    }

    @Test
    void rejectsMissingEmail() {
        OAuthProfile profile = OAuthProfile.builder()
                .provider(OAuthProvider.GITHUB)
                .providerUserId("42")
                .email(null)
                .build();
        assertThrows(BusinessException.class, () -> service.findOrCreateUser(profile));
    }

    @Test
    void createsNewUserAndBinding() {
        when(bindingMapper.selectOne(any())).thenReturn(null);
        when(sysUserMapper.selectOne(any())).thenReturn(null);
        doAnswer(inv -> {
            SysUserEntity u = inv.getArgument(0);
            u.setId(1001L);
            return 1;
        }).when(sysUserMapper).insert(any(SysUserEntity.class));
        when(bindingMapper.insert(any(UserOAuthBindingEntity.class))).thenReturn(1);

        OAuthProfile profile = OAuthProfile.builder()
                .provider(OAuthProvider.GOOGLE)
                .providerUserId("sub-1")
                .email("User@Example.COM")
                .displayName("Alice")
                .build();

        SysUserEntity user = service.findOrCreateUser(profile);
        assertEquals(1001L, user.getId());
        assertEquals("user@example.com", user.getEmail());
        assertNull(user.getPasswordHash());
        assertEquals(1, user.getEmailVerified());

        ArgumentCaptor<UserOAuthBindingEntity> cap = ArgumentCaptor.forClass(UserOAuthBindingEntity.class);
        verify(bindingMapper).insert(cap.capture());
        assertEquals("GOOGLE", cap.getValue().getProvider());
        assertEquals("sub-1", cap.getValue().getProviderUserId());
        assertEquals(1001L, cap.getValue().getUserId());
    }

    @Test
    void linksExistingEmail() {
        when(bindingMapper.selectOne(any())).thenReturn(null);
        SysUserEntity existing = new SysUserEntity();
        existing.setId(77L);
        existing.setEmail("a@b.com");
        when(sysUserMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(bindingMapper.insert(any())).thenReturn(1);

        OAuthProfile profile = OAuthProfile.builder()
                .provider(OAuthProvider.GITHUB)
                .providerUserId("gh-9")
                .email("a@b.com")
                .displayName("Bob")
                .build();

        SysUserEntity user = service.findOrCreateUser(profile);
        assertEquals(77L, user.getId());
        verify(sysUserMapper, never()).insert(any());
        verify(bindingMapper).insert(any());
    }

    @Test
    void returnsExistingBinding() {
        UserOAuthBindingEntity binding = new UserOAuthBindingEntity();
        binding.setId(1L);
        binding.setUserId(55L);
        binding.setProvider("GOOGLE");
        binding.setProviderUserId("sub-x");
        when(bindingMapper.selectOne(any())).thenReturn(binding);
        SysUserEntity user = new SysUserEntity();
        user.setId(55L);
        user.setEmail("x@y.com");
        when(sysUserMapper.selectById(55L)).thenReturn(user);

        OAuthProfile profile = OAuthProfile.builder()
                .provider(OAuthProvider.GOOGLE)
                .providerUserId("sub-x")
                .email("x@y.com")
                .build();

        assertEquals(55L, service.findOrCreateUser(profile).getId());
        verify(sysUserMapper, never()).insert(any());
        verify(bindingMapper, never()).insert(any());
    }
}
