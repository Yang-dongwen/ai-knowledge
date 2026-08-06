package com.dwcode.okxbot.auth.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;

/**
 * 生产类环境：禁止 OAuth/微信 mock 误开、微信密钥不全却声称启用、默认超管弱口令。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthConfigValidator {

    private static final String DEFAULT_ADMIN_PASSWORD = "Admin@123456";

    private final AuthProperties authProperties;
    private final Environment environment;

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        boolean prodLike = isProdLike();

        if (prodLike && authProperties.getOauth().isMock()) {
            throw new IllegalStateException(
                    "生产类环境（prod/production/ec2）禁止 auth.oauth.mock=true，请设置 AUTH_OAUTH_MOCK=false");
        }
        if (authProperties.getOauth().isMock()) {
            log.warn("auth.oauth.mock=true：OAuth 走 mock 用户（勿用于生产）");
        }

        AuthProperties.Wechat.Mini mini = authProperties.getWechat().getMini();
        if (mini != null && mini.isEnabled()) {
            boolean keysOk = StringUtils.hasText(mini.getAppId()) && StringUtils.hasText(mini.getAppSecret());
            if (!keysOk) {
                if (prodLike) {
                    throw new IllegalStateException(
                            "auth.wechat.mini.enabled=true 但 app-id/app-secret 未配齐；"
                                    + "请填写 WX_MINI_APP_ID/WX_MINI_APP_SECRET，或设 WX_MINI_ENABLED=false");
                }
                log.warn("微信小程序 enabled=true 但密钥不全，运行期将拒绝真实登录并禁止静默 mock");
            }
        } else if (mini != null && !mini.isEnabled()) {
            log.info("微信小程序登录未启用（mock openid 仅本地可用）");
        }

        if (prodLike && authProperties.getAdmin().isSeedEnabled()) {
            String pwd = authProperties.getAdmin().getPassword();
            if (!StringUtils.hasText(pwd) || DEFAULT_ADMIN_PASSWORD.equals(pwd)) {
                throw new IllegalStateException(
                        "生产类环境 seed 超管时必须设置强随机 AUTH_ADMIN_PASSWORD（禁止默认 Admin@123456）");
            }
        }

        if (prodLike) {
            String secret = authProperties.getJwt().getSecret();
            if (!StringUtils.hasText(secret)
                    || secret.contains("change-me")
                    || secret.length() < 32) {
                throw new IllegalStateException(
                        "生产类环境 auth.jwt.secret 必须为足够长的随机串（AUTH_JWT_SECRET，≥32 字节）");
            }
        }
    }

    private boolean isProdLike() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "prod".equalsIgnoreCase(p)
                        || "production".equalsIgnoreCase(p)
                        || "ec2".equalsIgnoreCase(p));
    }
}
