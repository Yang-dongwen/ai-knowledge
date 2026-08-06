package com.dwcode.okxbot.auth.security;

import com.dwcode.okxbot.auth.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
@RequiredArgsConstructor
public class JwtService {

    public static final String CLAIM_TOKEN_VERSION = "tv";

    private final AuthProperties authProperties;

    public String generateToken(Long userId, String email) {
        return generateToken(userId, email, 0);
    }

    public String generateToken(Long userId, String email, int tokenVersion) {
        long now = System.currentTimeMillis();
        long exp = now + authProperties.getJwt().getExpireSeconds() * 1000L;
        return Jwts.builder()
                .issuer(authProperties.getJwt().getIssuer())
                .subject(String.valueOf(userId))
                .claim("email", email)
                .claim(CLAIM_TOKEN_VERSION, tokenVersion)
                .issuedAt(new Date(now))
                .expiration(new Date(exp))
                .signWith(secretKey())
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey())
                .requireIssuer(authProperties.getJwt().getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long getUserId(String token) {
        return Long.parseLong(parseClaims(token).getSubject());
    }

    public int getTokenVersion(Claims claims) {
        if (claims == null) {
            return 0;
        }
        Object v = claims.get(CLAIM_TOKEN_VERSION);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        // 旧 token 无 tv claim → 视为 0（与 DB 默认一致）
        return 0;
    }

    public boolean isValid(String token) {
        try {
            Claims claims = parseClaims(token);
            return claims.getExpiration() != null && claims.getExpiration().after(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    private SecretKey secretKey() {
        String secret = authProperties.getJwt().getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("auth.jwt.secret 未配置");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        // HS256 要求足够熵；禁止短密钥补零
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "auth.jwt.secret 至少需要 32 字节（当前 " + keyBytes.length + "），请使用足够长的随机串");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
