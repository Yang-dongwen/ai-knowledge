package com.dwcode.okxbot.auth.oauth;

import com.dwcode.okxbot.auth.config.AuthProperties;
import com.dwcode.okxbot.common.exception.BusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth state / one-time ticket：HMAC 签名 + 本地 jti 防重放（短 TTL）。
 * 多实例部署时重放防护为尽力而为，依赖短过期时间。
 */
@Component
@RequiredArgsConstructor
public class OAuthTokenStore {

    private static final String ISSUER = "okx-bot-oauth";
    private static final String TYPE_STATE = "state";
    private static final String TYPE_TICKET = "ticket";

    private final AuthProperties authProperties;
    /** jti → 过期毫秒时间戳 */
    private final Map<String, Long> usedJti = new ConcurrentHashMap<>();

    public String createState(OAuthProvider provider, String redirectPath) {
        long ttl = Math.max(30, authProperties.getOauth().getTicketTtlSeconds());
        long now = System.currentTimeMillis();
        String jti = UUID.randomUUID().toString().replace("-", "");
        return Jwts.builder()
                .issuer(ISSUER)
                .id(jti)
                .claim("typ", TYPE_STATE)
                .claim("provider", provider.name())
                .claim("redirect", redirectPath == null ? "" : redirectPath)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttl * 1000L))
                .signWith(secretKey())
                .compact();
    }

    public StatePayload consumeState(String state, OAuthProvider expected) {
        Claims claims = parseAndConsume(state, TYPE_STATE);
        String provider = claims.get("provider", String.class);
        if (!expected.name().equals(provider)) {
            throw new BusinessException(400, "OAuth state 与提供方不匹配");
        }
        String redirect = claims.get("redirect", String.class);
        return new StatePayload(expected, redirect == null ? "" : redirect);
    }

    public String createTicket(Long userId) {
        long ttl = Math.max(30, authProperties.getOauth().getTicketTtlSeconds());
        long now = System.currentTimeMillis();
        String jti = UUID.randomUUID().toString().replace("-", "");
        return Jwts.builder()
                .issuer(ISSUER)
                .id(jti)
                .claim("typ", TYPE_TICKET)
                .subject(String.valueOf(userId))
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttl * 1000L))
                .signWith(secretKey())
                .compact();
    }

    public Long consumeTicket(String ticket) {
        Claims claims = parseAndConsume(ticket, TYPE_TICKET);
        try {
            return Long.parseLong(claims.getSubject());
        } catch (Exception e) {
            throw new BusinessException(400, "登录凭证无效");
        }
    }

    private Claims parseAndConsume(String token, String expectedType) {
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(400, "登录凭证无效或已过期");
        }
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(secretKey())
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token.trim())
                    .getPayload();
        } catch (Exception e) {
            throw new BusinessException(400, "登录凭证无效或已过期");
        }
        String typ = claims.get("typ", String.class);
        if (!expectedType.equals(typ)) {
            throw new BusinessException(400, "登录凭证类型错误");
        }
        Date exp = claims.getExpiration();
        if (exp == null || exp.before(new Date())) {
            throw new BusinessException(400, "登录凭证无效或已过期");
        }
        String jti = claims.getId();
        if (!StringUtils.hasText(jti)) {
            throw new BusinessException(400, "登录凭证无效或已过期");
        }
        purgeExpiredJti();
        Long prev = usedJti.putIfAbsent(jti, exp.getTime());
        if (prev != null) {
            throw new BusinessException(400, "登录凭证已使用，请重新登录");
        }
        return claims;
    }

    private void purgeExpiredJti() {
        long now = System.currentTimeMillis();
        usedJti.entrySet().removeIf(e -> e.getValue() != null && e.getValue() < now);
    }

    private SecretKey secretKey() {
        // 与业务 JWT 同密钥族，加固定前缀避免与业务 token 混用
        String raw = "oauth:" + authProperties.getJwt().getSecret();
        byte[] keyBytes = raw.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, Math.min(keyBytes.length, 32));
            keyBytes = padded;
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public record StatePayload(OAuthProvider provider, String redirectPath) {
    }
}
