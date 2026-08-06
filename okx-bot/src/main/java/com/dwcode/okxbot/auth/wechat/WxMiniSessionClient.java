package com.dwcode.okxbot.auth.wechat;

import com.dwcode.okxbot.auth.config.AuthProperties;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * 微信小程序 code → openid（jscode2session）。
 * <p>mock 仅在 {@code enabled=false} 且 {@code mock=true} 时可用；
 * enabled=true 但密钥不全时拒绝，不再静默 mock。
 */
@Slf4j
@Component
public class WxMiniSessionClient {

    private static final String JSCODE2SESSION =
            "https://api.weixin.qq.com/sns/jscode2session";

    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    public WxMiniSessionClient(AuthProperties authProperties, ObjectMapper objectMapper) {
        this.authProperties = authProperties;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(8))
                .readTimeout(Duration.ofSeconds(12))
                .build();
    }

    /**
     * @return 稳定 openid（mock 时为 mock 前缀 + 规范化 code）
     */
    public String resolveOpenid(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(400, "微信登录 code 无效");
        }
        String c = code.trim();
        AuthProperties.Wechat.Mini mini = authProperties.getWechat().getMini();
        if (mini == null) {
            throw new BusinessException(503, "微信登录未配置");
        }
        if (mini.isEnabled()) {
            if (!StringUtils.hasText(mini.getAppId()) || !StringUtils.hasText(mini.getAppSecret())) {
                throw new BusinessException(503,
                        "微信登录已启用但未配置 AppId/AppSecret，请联系管理员");
            }
            return realOpenid(mini.getAppId().trim(), mini.getAppSecret().trim(), c);
        }
        if (!mini.isMock()) {
            throw new BusinessException(503, "微信登录未启用");
        }
        return mockOpenid(c);
    }

    private String mockOpenid(String code) {
        String raw = code;
        if (raw.toLowerCase(Locale.ROOT).startsWith("mock:")) {
            raw = raw.substring(5);
        }
        raw = raw.replaceAll("[^A-Za-z0-9_-]", "");
        if (!StringUtils.hasText(raw)) {
            throw new BusinessException(400, "微信登录 code 无效");
        }
        if (raw.length() > 48) {
            raw = raw.substring(0, 48);
        }
        String openid = "mock_" + raw;
        log.info("wx mini mock openid: openid={}", openid);
        return openid;
    }

    private String realOpenid(String appId, String secret, String code) {
        String url = JSCODE2SESSION
                + "?appid=" + enc(appId)
                + "&secret=" + enc(secret)
                + "&js_code=" + enc(code)
                + "&grant_type=authorization_code";
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.warn("jscode2session http fail status={} body={}", response.code(), body);
                throw new BusinessException(502, "微信登录服务暂不可用");
            }
            JsonNode node = objectMapper.readTree(body);
            if (node.hasNonNull("errcode") && node.get("errcode").asInt() != 0) {
                int err = node.get("errcode").asInt();
                String msg = node.path("errmsg").asText("unknown");
                log.warn("jscode2session errcode={} errmsg={}", err, msg);
                if (err == 40029 || err == 40163) {
                    throw new BusinessException(400, "微信登录凭证无效，请重试");
                }
                throw new BusinessException(400, "微信登录失败: " + msg);
            }
            String openid = node.path("openid").asText(null);
            if (!StringUtils.hasText(openid)) {
                throw new BusinessException(502, "微信未返回 openid");
            }
            return openid.trim();
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            log.warn("jscode2session io error: {}", e.getMessage());
            throw new BusinessException(502, "微信登录网络失败");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
