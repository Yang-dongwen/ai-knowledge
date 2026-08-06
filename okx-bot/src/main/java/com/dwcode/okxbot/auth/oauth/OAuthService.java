package com.dwcode.okxbot.auth.oauth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.auth.config.AuthProperties;
import com.dwcode.okxbot.auth.dto.LoginResponse;
import com.dwcode.okxbot.auth.dto.OAuthProvidersResponse;
import com.dwcode.okxbot.auth.entity.SysUserEntity;
import com.dwcode.okxbot.auth.enums.UserRole;
import com.dwcode.okxbot.auth.mapper.SysUserMapper;
import com.dwcode.okxbot.auth.oauth.entity.UserOAuthBindingEntity;
import com.dwcode.okxbot.auth.oauth.mapper.UserOAuthBindingMapper;
import com.dwcode.okxbot.auth.service.AuthService;
import com.dwcode.okxbot.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthService {

    private final AuthProperties authProperties;
    private final OAuthTokenStore tokenStore;
    private final JustAuthClient justAuthClient;
    private final UserOAuthBindingMapper bindingMapper;
    private final SysUserMapper sysUserMapper;
    private final AuthService authService;

    public OAuthProvidersResponse listProviders() {
        List<String> list = new ArrayList<>();
        AuthProperties.OAuth oauth = authProperties.getOauth();
        if (oauth.isMock()) {
            list.add(OAuthProvider.GOOGLE.path());
            list.add(OAuthProvider.GITHUB.path());
        } else {
            if (oauth.getGoogle().isEnabled()) {
                list.add(OAuthProvider.GOOGLE.path());
            }
            if (oauth.getGithub().isEnabled()) {
                list.add(OAuthProvider.GITHUB.path());
            }
        }
        return OAuthProvidersResponse.builder()
                .providers(list)
                .mock(oauth.isMock())
                .build();
    }

    /**
     * 生成跳转第三方授权页的 URL；mock 模式下直接指向本系统 callback。
     */
    public String buildAuthorizeRedirect(OAuthProvider provider, String redirectPath) {
        assertProviderAvailable(provider);
        String safeRedirect = sanitizeRedirect(redirectPath);
        String state = tokenStore.createState(provider, safeRedirect);
        if (authProperties.getOauth().isMock()) {
            String base = trimTrailingSlash(authProperties.getOauth().getCallbackBaseUrl());
            return UriComponentsBuilder
                    .fromHttpUrl(base + "/api/auth/oauth/" + provider.path() + "/callback")
                    .queryParam("code", "mock")
                    .queryParam("state", state)
                    .build(true)
                    .toUriString();
        }
        return justAuthClient.buildAuthorizeUrl(provider, state);
    }

    /**
     * 平台回调：换资料 → 找/建用户 → ticket → 前端 URL。
     */
    @Transactional
    public String handleCallback(OAuthProvider provider, String code, String state) {
        OAuthTokenStore.StatePayload payload = tokenStore.consumeState(state, provider);
        OAuthProfile profile;
        if (authProperties.getOauth().isMock()) {
            profile = mockProfile(provider, code);
        } else {
            profile = justAuthClient.exchangeCode(provider, code, state);
        }
        SysUserEntity user = findOrCreateUser(profile);
        authService.assertUserCanLogin(user);
        authService.touchLogin(user);
        String ticket = tokenStore.createTicket(user.getId());
        log.info("oauth callback ok provider={} userId={} providerUserId={}",
                provider, user.getId(), maskId(profile.getProviderUserId()));
        return buildFrontendCallbackUrl(ticket, payload.redirectPath(), null);
    }

    public String buildErrorRedirect(String errorCode) {
        return buildFrontendCallbackUrl(null, null, errorCode == null ? "oauth_failed" : errorCode);
    }

    public LoginResponse exchangeTicket(String ticket) {
        Long userId = tokenStore.consumeTicket(ticket);
        SysUserEntity user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(401, "用户不存在或已删除");
        }
        authService.assertUserCanLogin(user);
        return authService.buildLoginResponse(user);
    }

    @Transactional
    public SysUserEntity findOrCreateUser(OAuthProfile profile) {
        if (profile == null || profile.getProvider() == null || !StringUtils.hasText(profile.getProviderUserId())) {
            throw new BusinessException(400, "第三方用户资料不完整");
        }
        String email = normalizeEmail(profile.getEmail());
        if (!StringUtils.hasText(email)) {
            throw new BusinessException(400, "第三方账号未提供可用邮箱，请在 GitHub/Google 公开邮箱后重试");
        }

        UserOAuthBindingEntity binding = bindingMapper.selectOne(
                new LambdaQueryWrapper<UserOAuthBindingEntity>()
                        .eq(UserOAuthBindingEntity::getProvider, profile.getProvider().name())
                        .eq(UserOAuthBindingEntity::getProviderUserId, profile.getProviderUserId())
        );
        if (binding != null) {
            SysUserEntity user = sysUserMapper.selectById(binding.getUserId());
            if (user == null) {
                throw new BusinessException(500, "绑定账号已失效，请联系管理员");
            }
            refreshBinding(binding, profile);
            return user;
        }

        SysUserEntity byEmail = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUserEntity>().eq(SysUserEntity::getEmail, email)
        );
        LocalDateTime now = LocalDateTime.now();
        SysUserEntity user;
        if (byEmail != null) {
            // 默认禁止邮箱自动绑定，防止第三方未验证邮箱接管已有账号
            if (!authProperties.getOauth().isAutoLinkByEmail()) {
                throw new BusinessException(409,
                        "该邮箱已注册，请使用邮箱密码登录后在设置中绑定第三方账号");
            }
            user = byEmail;
            log.info("oauth auto-link by email (enabled) provider={} userId={} email={}",
                    profile.getProvider(), user.getId(), maskEmail(email));
        } else {
            user = new SysUserEntity();
            user.setEmail(email);
            user.setPasswordHash(null);
            user.setTokenVersion(0);
            String nickname = StringUtils.hasText(profile.getDisplayName())
                    ? profile.getDisplayName().trim()
                    : email.substring(0, email.indexOf('@'));
            if (nickname.length() > 64) {
                nickname = nickname.substring(0, 64);
            }
            user.setNickname(nickname);
            user.setRole(UserRole.USER.name());
            user.setStatus(1);
            user.setEmailVerified(1);
            user.setCreatedAt(now);
            user.setUpdatedAt(now);
            sysUserMapper.insert(user);
            log.info("oauth register provider={} userId={} email={}",
                    profile.getProvider(), user.getId(), maskEmail(email));
        }

        UserOAuthBindingEntity created = new UserOAuthBindingEntity();
        created.setUserId(user.getId());
        created.setProvider(profile.getProvider().name());
        created.setProviderUserId(profile.getProviderUserId());
        created.setEmail(email);
        created.setDisplayName(profile.getDisplayName());
        created.setAvatarUrl(profile.getAvatarUrl());
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        bindingMapper.insert(created);
        return user;
    }

    private void refreshBinding(UserOAuthBindingEntity binding, OAuthProfile profile) {
        binding.setEmail(normalizeEmail(profile.getEmail()));
        binding.setDisplayName(profile.getDisplayName());
        binding.setAvatarUrl(profile.getAvatarUrl());
        binding.setUpdatedAt(LocalDateTime.now());
        bindingMapper.updateById(binding);
    }

    private OAuthProfile mockProfile(OAuthProvider provider, String code) {
        // code 可传 mock:user@example.com 指定邮箱，默认固定 mock 用户
        String email;
        String subject;
        if (StringUtils.hasText(code) && code.startsWith("mock:") && code.length() > 5) {
            String rest = code.substring(5).trim();
            if (rest.contains("@")) {
                email = normalizeEmail(rest);
                subject = "mock-" + provider.path() + "-" + email;
            } else {
                subject = "mock-" + provider.path() + "-" + rest;
                email = rest.replaceAll("[^a-zA-Z0-9._-]", "") + "@oauth.mock.local";
            }
        } else {
            subject = "mock-" + provider.path() + "-default";
            email = "mock." + provider.path() + "@oauth.mock.local";
        }
        return OAuthProfile.builder()
                .provider(provider)
                .providerUserId(subject)
                .email(email)
                .displayName("Mock " + provider.name())
                .avatarUrl(null)
                .build();
    }

    private void assertProviderAvailable(OAuthProvider provider) {
        if (authProperties.getOauth().isMock()) {
            return;
        }
        AuthProperties.OAuth.Provider cfg = switch (provider) {
            case GOOGLE -> authProperties.getOauth().getGoogle();
            case GITHUB -> authProperties.getOauth().getGithub();
        };
        if (!cfg.isEnabled()) {
            throw new BusinessException(400, provider.name() + " 登录未启用");
        }
    }

    String sanitizeRedirect(String redirect) {
        if (!StringUtils.hasText(redirect)) {
            return "/home";
        }
        String r = redirect.trim();
        if (!r.startsWith("/") || r.startsWith("//") || r.contains("://")) {
            throw new BusinessException(400, "非法回调路径");
        }
        // 去掉危险字符
        if (r.indexOf('\\') >= 0 || r.indexOf('\n') >= 0 || r.indexOf('\r') >= 0) {
            throw new BusinessException(400, "非法回调路径");
        }
        List<String> allowed = authProperties.getOauth().getAllowedRedirectPaths();
        if (allowed != null && !allowed.isEmpty()) {
            boolean ok = false;
            for (String prefix : allowed) {
                if (!StringUtils.hasText(prefix)) {
                    continue;
                }
                String p = prefix.trim();
                if (r.equals(p)
                        || r.startsWith(p.endsWith("/") ? p : p + "/")
                        || r.startsWith(p + "?")
                        || (p.equals("/") && r.startsWith("/"))) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                throw new BusinessException(400, "回调路径不在允许列表中");
            }
        }
        return r;
    }

    private String buildFrontendCallbackUrl(String ticket, String redirectPath, String error) {
        String base = trimTrailingSlash(authProperties.getOauth().getFrontendBaseUrl());
        UriComponentsBuilder b = UriComponentsBuilder.fromHttpUrl(base + "/oauth/callback");
        if (StringUtils.hasText(error)) {
            b.queryParam("oauth_error", error);
        }
        if (StringUtils.hasText(ticket)) {
            b.queryParam("ticket", ticket);
        }
        if (StringUtils.hasText(redirectPath)) {
            b.queryParam("redirect", redirectPath);
        }
        return b.build(true).toUriString();
    }

    private static String normalizeEmail(String email) {
        if (!StringUtils.hasText(email)) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String trimTrailingSlash(String url) {
        if (!StringUtils.hasText(url)) {
            return "http://localhost:3000";
        }
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static String maskEmail(String email) {
        if (!StringUtils.hasText(email) || !email.contains("@")) {
            return "***";
        }
        int at = email.indexOf('@');
        String name = email.substring(0, at);
        String domain = email.substring(at);
        if (name.length() <= 2) {
            return name.charAt(0) + "***" + domain;
        }
        return name.substring(0, 2) + "***" + domain;
    }

    private static String maskId(String id) {
        if (!StringUtils.hasText(id) || id.length() < 6) {
            return "***";
        }
        return id.substring(0, 3) + "…" + id.substring(id.length() - 3);
    }
}
