package com.dwcode.okxbot.blog;

import com.dwcode.okxbot.auth.security.AuthUserPrincipal;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.blog.adapter.DisabledHaloPublishAdapter;
import com.dwcode.okxbot.blog.adapter.HaloHttpPublishAdapter;
import com.dwcode.okxbot.blog.config.HaloProperties;
import com.dwcode.okxbot.blog.entity.UserHaloBindingEntity;
import com.dwcode.okxbot.blog.mapper.UserHaloBindingMapper;
import com.dwcode.okxbot.blog.port.HaloPublishPort;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 按当前用户解析 Halo 客户端。超管走平台 yml；其他人必须自绑。
 */
@Component
@RequiredArgsConstructor
public class HaloClientResolver {

    public static final String TARGET_PLATFORM = "platform";
    public static final String TARGET_PERSONAL = "personal";
    public static final String MSG_NEED_BIND = "请先关联博客账户";
    public static final String MSG_PLATFORM_OFF = DisabledHaloPublishAdapter.MESSAGE;

    private final HaloProperties platform;
    private final UserHaloBindingMapper bindingMapper;
    private final HaloTokenCipher tokenCipher;
    private final ObjectMapper objectMapper;

    public record Resolved(HaloPublishPort port, String publicBaseUrl, String target, String siteUrl) {
    }

    public Resolved resolve() {
        AuthUserPrincipal user = SecurityUtils.requireCurrentUser();
        if (user.isSuperAdmin()) {
            if (!platform.isConfigured()) {
                throw new BusinessException(503, MSG_PLATFORM_OFF);
            }
            return new Resolved(
                    new HaloHttpPublishAdapter(platform, objectMapper),
                    platform.getPublicBaseUrl(),
                    TARGET_PLATFORM,
                    platform.getPublicBaseUrl());
        }
        UserHaloBindingEntity b = bindingMapper.selectById(user.getId());
        if (b == null || !StringUtils.hasText(b.getTokenCipher())) {
            throw new BusinessException(403, MSG_NEED_BIND);
        }
        String token = tokenCipher.decrypt(b.getTokenCipher());
        HaloProperties props = new HaloProperties();
        props.setEnabled(true);
        props.setBaseUrl(b.getBaseUrl());
        props.setToken(token);
        props.setPublicBaseUrl(StringUtils.hasText(b.getPublicBaseUrl()) ? b.getPublicBaseUrl() : b.getBaseUrl());
        props.setPublishOnCreate(platform.isPublishOnCreate());
        return new Resolved(
                new HaloHttpPublishAdapter(props, objectMapper),
                props.getPublicBaseUrl(),
                TARGET_PERSONAL,
                props.getPublicBaseUrl());
    }

    public boolean sameSite(String permalink, String publicBaseUrl) {
        if (!StringUtils.hasText(permalink) || !StringUtils.hasText(publicBaseUrl)) {
            return false;
        }
        String base = HaloHttpPublishAdapter.trimSlash(publicBaseUrl);
        String url = permalink.trim();
        return url.equals(base) || url.startsWith(base + "/");
    }
}
