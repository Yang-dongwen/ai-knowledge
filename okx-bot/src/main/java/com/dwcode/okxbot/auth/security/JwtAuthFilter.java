package com.dwcode.okxbot.auth.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 从 Authorization: Bearer 或（仅媒体 GET）查询参数 {@code access_token}/{@code token} 解析 JWT。
 * <p>校验 token_version（改密吊销旧会话）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    /**
     * 允许 query JWT 的路径（浏览器 &lt;video&gt;/&lt;img&gt; 无法带 Authorization）。
     * 其它接口必须用 Header，避免日志/Referer 泄漏整站会话。
     */
    private static final Pattern MEDIA_QUERY_TOKEN_PATH = Pattern.compile(
            "^/api/v1/(kb/files/\\d+/content"
                    + "|video/tasks/\\d+/video"
                    + "|aigen/tasks/\\d+/media/output"
                    + "|aigen/tasks/\\d+/shots/\\d+/image"
                    + "|imggen/tasks/\\d+/media/[^/]+)$",
            Pattern.CASE_INSENSITIVE
    );

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null && !token.isBlank()
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                if (jwtService.isValid(token)) {
                    Claims claims = jwtService.parseClaims(token);
                    Long userId = Long.parseLong(claims.getSubject());
                    AuthUserPrincipal principal = userDetailsService.loadById(userId);
                    int claimTv = jwtService.getTokenVersion(claims);
                    if (claimTv != principal.getTokenVersion()) {
                        log.debug("JWT token_version 不匹配 userId={} claim={} db={}",
                                userId, claimTv, principal.getTokenVersion());
                        SecurityContextHolder.clearContext();
                    } else if (principal.isEnabled()) {
                        UsernamePasswordAuthenticationToken auth =
                                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                }
            } catch (Exception e) {
                log.debug("JWT 解析失败: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private static String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String t = header.substring(7).trim();
            if (!t.isEmpty()) {
                return t;
            }
        }
        // 仅媒体 GET 允许 query token
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        String path = request.getRequestURI();
        if (path == null) {
            return null;
        }
        // 去掉 context-path
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
        }
        if (!MEDIA_QUERY_TOKEN_PATH.matcher(path).matches()) {
            return null;
        }
        String q = request.getParameter("access_token");
        if (q == null || q.isBlank()) {
            q = request.getParameter("token");
        }
        return q != null && !q.isBlank() ? q.trim() : null;
    }
}
