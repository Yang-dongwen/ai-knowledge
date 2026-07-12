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

    private final AuthProperties authProperties;

    public String generateToken(Long userId, String email) {
        long now = System.currentTimeMillis();
        long exp = now + authProperties.getJwt().getExpireSeconds() * 1000L;
        return Jwts.builder()
                .issuer(authProperties.getJwt().getIssuer())
                .subject(String.valueOf(userId))
                .claim("email", email)
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

    public boolean isValid(String token) {
        try {
            Claims claims = parseClaims(token);
            return claims.getExpiration() != null && claims.getExpiration().after(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    private SecretKey secretKey() {
        byte[] keyBytes = authProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        // HS256 要求足够长度
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, Math.min(keyBytes.length, 32));
            keyBytes = padded;
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
