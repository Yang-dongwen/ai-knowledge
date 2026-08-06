package com.dwcode.okxbot.auth.oauth;

import com.dwcode.okxbot.auth.config.AuthProperties;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xkcoding.http.config.HttpConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthResponse;
import me.zhyd.oauth.model.AuthToken;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthGithubRequest;
import me.zhyd.oauth.request.AuthGoogleRequest;
import me.zhyd.oauth.request.AuthRequest;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JustAuth 封装：生成授权 URL、用 code 换用户资料。
 * GitHub 邮箱常为隐私：需 scope user:email，并调用 /user/emails。
 * 本机访问 GitHub 常需 HTTP 代理（auth.oauth.proxy-*）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JustAuthClient {

    private static final String GITHUB_EMAILS_URL = "https://api.github.com/user/emails";

    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;

    private OkHttpClient httpClient;

    @PostConstruct
    void initHttp() {
        this.httpClient = buildOkHttpClient();
    }

    public String buildAuthorizeUrl(OAuthProvider provider, String state) {
        AuthRequest request = createRequest(provider);
        return request.authorize(state);
    }

    public OAuthProfile exchangeCode(OAuthProvider provider, String code, String state) {
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(400, "授权码无效");
        }
        AuthRequest request = createRequest(provider);
        AuthCallback callback = AuthCallback.builder()
                .code(code)
                .state(state)
                .build();
        AuthResponse<AuthUser> response;
        try {
            @SuppressWarnings("unchecked")
            AuthResponse<AuthUser> r = request.login(callback);
            response = r;
        } catch (Exception e) {
            log.error("oauth justauth login failed provider={}: {}", provider, e.getMessage(), e);
            throw new BusinessException(400,
                    "第三方授权失败（无法访问 " + provider + " 接口，请检查网络/代理）: "
                            + brief(e.getMessage()));
        }
        if (response == null || !response.ok() || response.getData() == null) {
            String msg = response != null ? response.getMsg() : "empty response";
            log.warn("oauth exchange failed provider={} msg={}", provider, msg);
            throw new BusinessException(400, "第三方授权失败: " + brief(msg));
        }
        AuthUser user = response.getData();
        String providerUserId = StringUtils.hasText(user.getUuid()) ? user.getUuid() : user.getUsername();
        if (!StringUtils.hasText(providerUserId)) {
            throw new BusinessException(400, "未能获取第三方用户标识");
        }
        String email = StringUtils.hasText(user.getEmail()) ? user.getEmail().trim() : null;
        if (!StringUtils.hasText(email) && provider == OAuthProvider.GITHUB) {
            email = resolveGithubEmail(user);
        }
        if (!StringUtils.hasText(email) && provider == OAuthProvider.GITHUB) {
            String login = StringUtils.hasText(user.getUsername()) ? user.getUsername().trim() : "user";
            email = providerUserId + "+" + login + "@users.noreply.github.com";
            log.info("oauth github using noreply email for uuid={}", mask(providerUserId));
        }
        String displayName = firstNonBlank(user.getNickname(), user.getUsername(), email);
        return OAuthProfile.builder()
                .provider(provider)
                .providerUserId(providerUserId.trim())
                .email(StringUtils.hasText(email) ? email.trim() : null)
                .displayName(displayName)
                .avatarUrl(user.getAvatar())
                .build();
    }

    private String resolveGithubEmail(AuthUser user) {
        AuthToken token = user.getToken();
        if (token == null || !StringUtils.hasText(token.getAccessToken())) {
            log.warn("oauth github no access token for email lookup");
            return null;
        }
        if (httpClient == null) {
            httpClient = buildOkHttpClient();
        }
        Request req = new Request.Builder()
                .url(GITHUB_EMAILS_URL)
                .header("Authorization", "Bearer " + token.getAccessToken())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "okx-bot-oauth")
                .get()
                .build();
        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("oauth github emails api status={}", resp.code());
                return null;
            }
            String body = resp.body().string();
            JsonNode arr = objectMapper.readTree(body);
            if (!arr.isArray() || arr.isEmpty()) {
                return null;
            }
            String primaryVerified = null;
            String anyVerified = null;
            String any = null;
            for (JsonNode n : arr) {
                String e = n.path("email").asText(null);
                if (!StringUtils.hasText(e)) {
                    continue;
                }
                boolean primary = n.path("primary").asBoolean(false);
                boolean verified = n.path("verified").asBoolean(false);
                if (any == null) {
                    any = e;
                }
                if (verified && anyVerified == null) {
                    anyVerified = e;
                }
                if (primary && verified) {
                    primaryVerified = e;
                    break;
                }
            }
            String chosen = firstNonBlank(primaryVerified, anyVerified, any);
            if (StringUtils.hasText(chosen)) {
                log.info("oauth github email resolved via /user/emails");
            }
            return chosen;
        } catch (Exception e) {
            log.warn("oauth github emails lookup failed: {}", e.getMessage());
            return null;
        }
    }

    private AuthRequest createRequest(OAuthProvider provider) {
        AuthProperties.OAuth.Provider cfg = providerConfig(provider);
        if (!cfg.isEnabled()) {
            throw new BusinessException(400, provider.name() + " 登录未启用");
        }
        if (!StringUtils.hasText(cfg.getClientId()) || !StringUtils.hasText(cfg.getClientSecret())) {
            throw new BusinessException(500, provider.name() + " OAuth 未配置 client-id/secret");
        }
        String redirectUri = callbackUri(provider);
        AuthConfig.AuthConfigBuilder builder = AuthConfig.builder()
                .clientId(cfg.getClientId().trim())
                .clientSecret(cfg.getClientSecret().trim())
                .redirectUri(redirectUri)
                .ignoreCheckState(true)
                .httpConfig(buildJustAuthHttpConfig());
        if (provider == OAuthProvider.GITHUB) {
            builder.scopes(List.of("read:user", "user:email"));
        } else if (provider == OAuthProvider.GOOGLE) {
            builder.scopes(List.of("openid", "email", "profile"));
        }
        AuthConfig authConfig = builder.build();
        return switch (provider) {
            case GOOGLE -> new AuthGoogleRequest(authConfig);
            case GITHUB -> new AuthGithubRequest(authConfig);
        };
    }

    private HttpConfig buildJustAuthHttpConfig() {
        HttpConfig config = new HttpConfig();
        config.setTimeout(20000);
        Proxy proxy = resolveProxy();
        if (proxy != null) {
            config.setProxy(proxy);
        }
        return config;
    }

    private OkHttpClient buildOkHttpClient() {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS);
        Proxy proxy = resolveProxy();
        if (proxy != null) {
            b.proxy(proxy);
            log.info("OAuth OkHttp 已配置代理: {}", proxy);
        } else {
            log.info("OAuth OkHttp 直连（未配置 auth.oauth.proxy-host/port）；本机访问 GitHub 失败时请配 Clash 代理");
        }
        return b.build();
    }

    private Proxy resolveProxy() {
        AuthProperties.OAuth oauth = authProperties.getOauth();
        String host = oauth.getProxyHost() == null ? "" : oauth.getProxyHost().trim();
        int port = oauth.getProxyPort();
        if (host.isEmpty() || port <= 0) {
            return null;
        }
        Proxy.Type type = "SOCKS".equalsIgnoreCase(
                oauth.getProxyType() == null ? "HTTP" : oauth.getProxyType().trim())
                ? Proxy.Type.SOCKS
                : Proxy.Type.HTTP;
        return new Proxy(type, new InetSocketAddress(host, port));
    }

    public String callbackUri(OAuthProvider provider) {
        String base = trimTrailingSlash(authProperties.getOauth().getCallbackBaseUrl());
        return base + "/api/auth/oauth/" + provider.path() + "/callback";
    }

    private AuthProperties.OAuth.Provider providerConfig(OAuthProvider provider) {
        return switch (provider) {
            case GOOGLE -> authProperties.getOauth().getGoogle();
            case GITHUB -> authProperties.getOauth().getGithub();
        };
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:8080";
        }
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (StringUtils.hasText(v)) {
                return v.trim();
            }
        }
        return null;
    }

    private static String mask(String id) {
        if (!StringUtils.hasText(id) || id.length() < 4) {
            return "***";
        }
        return id.substring(0, 2) + "…";
    }

    private static String brief(String msg) {
        if (msg == null) {
            return "";
        }
        String m = msg.replaceAll("[\\r\\n]+", " ").trim();
        return m.length() > 160 ? m.substring(0, 160) + "…" : m;
    }
}
