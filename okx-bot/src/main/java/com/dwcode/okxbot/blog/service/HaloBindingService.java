package com.dwcode.okxbot.blog.service;

import com.dwcode.okxbot.auth.security.AuthUserPrincipal;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.blog.HaloClientResolver;
import com.dwcode.okxbot.blog.HaloConnectionProbe;
import com.dwcode.okxbot.blog.HaloTokenCipher;
import com.dwcode.okxbot.blog.adapter.HaloHttpPublishAdapter;
import com.dwcode.okxbot.blog.config.HaloProperties;
import com.dwcode.okxbot.blog.dto.HaloBindingRequest;
import com.dwcode.okxbot.blog.dto.HaloBindingResponse;
import com.dwcode.okxbot.blog.entity.UserHaloBindingEntity;
import com.dwcode.okxbot.blog.mapper.UserHaloBindingMapper;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class HaloBindingService {

    private final HaloProperties platform;
    private final UserHaloBindingMapper bindingMapper;
    private final HaloTokenCipher tokenCipher;
    private final ObjectMapper objectMapper;

    public HaloBindingResponse current() {
        AuthUserPrincipal user = SecurityUtils.requireCurrentUser();
        if (user.isSuperAdmin()) {
            boolean ok = platform.isConfigured();
            return HaloBindingResponse.builder()
                    .bound(ok)
                    .target(HaloClientResolver.TARGET_PLATFORM)
                    .siteUrl(ok ? HaloHttpPublishAdapter.trimSlash(platform.getPublicBaseUrl()) : "")
                    .publicUrl(ok ? HaloHttpPublishAdapter.trimSlash(platform.getPublicBaseUrl()) : "")
                    .haloUsername(ok ? "platform" : "")
                    .tokenMasked(null)
                    .hint(ok ? "超级管理员发文到平台博客" : HaloClientResolver.MSG_PLATFORM_OFF)
                    .build();
        }
        UserHaloBindingEntity b = bindingMapper.selectById(user.getId());
        if (b == null) {
            return HaloBindingResponse.builder()
                    .bound(false)
                    .target(HaloClientResolver.TARGET_PERSONAL)
                    .hint(HaloClientResolver.MSG_NEED_BIND)
                    .build();
        }
        return HaloBindingResponse.builder()
                .bound(true)
                .target(HaloClientResolver.TARGET_PERSONAL)
                .siteUrl(HaloHttpPublishAdapter.trimSlash(b.getBaseUrl()))
                .publicUrl(HaloHttpPublishAdapter.trimSlash(b.getPublicBaseUrl()))
                .haloUsername(b.getHaloUsername())
                .tokenMasked(maskStored(b.getTokenCipher()))
                .hint("已关联个人博客")
                .build();
    }

    public HaloBindingResponse save(HaloBindingRequest request) {
        AuthUserPrincipal user = SecurityUtils.requireCurrentUser();
        if (user.isSuperAdmin()) {
            throw new BusinessException(400, "超级管理员使用平台博客配置，无需个人绑定");
        }
        String base = normalizeUrl(request.getBaseUrl());
        String pub = StringUtils.hasText(request.getPublicBaseUrl())
                ? normalizeUrl(request.getPublicBaseUrl())
                : base;
        UserHaloBindingEntity existing = bindingMapper.selectById(user.getId());
        String token = request.getToken() == null ? "" : request.getToken().trim();
        if (!StringUtils.hasText(token)) {
            if (existing == null) {
                throw new BusinessException(400, "请填写个人令牌");
            }
            token = tokenCipher.decrypt(existing.getTokenCipher());
        }
        HaloConnectionProbe.Result probe = HaloConnectionProbe.probe(base, token, objectMapper);
        LocalDateTime now = LocalDateTime.now();
        UserHaloBindingEntity e = existing == null ? new UserHaloBindingEntity() : existing;
        e.setUserId(user.getId());
        e.setBaseUrl(base);
        e.setPublicBaseUrl(pub);
        e.setTokenCipher(tokenCipher.encrypt(token));
        e.setHaloUsername(probe.username());
        e.setVerifiedAt(now);
        e.setUpdatedAt(now);
        if (existing == null) {
            e.setCreatedAt(now);
            bindingMapper.insert(e);
        } else {
            bindingMapper.updateById(e);
        }
        log.info("halo binding saved userId={} site={} haloUser={}", user.getId(), pub, probe.username());
        return current();
    }

    public void delete() {
        AuthUserPrincipal user = SecurityUtils.requireCurrentUser();
        if (user.isSuperAdmin()) {
            throw new BusinessException(400, "超级管理员使用平台博客配置，无需解除");
        }
        bindingMapper.deleteById(user.getId());
        log.info("halo binding deleted userId={}", user.getId());
    }

    private String maskStored(String cipher) {
        try {
            return HaloTokenCipher.mask(tokenCipher.decrypt(cipher));
        } catch (Exception e) {
            return "****";
        }
    }

    static String normalizeUrl(String raw) {
        String s = HaloHttpPublishAdapter.trimSlash(raw);
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            throw new BusinessException(400, "站点地址须以 http:// 或 https:// 开头");
        }
        return s;
    }
}
