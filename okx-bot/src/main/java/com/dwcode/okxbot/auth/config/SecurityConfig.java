package com.dwcode.okxbot.auth.config;

import com.dwcode.okxbot.auth.security.JwtAuthFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dwcode.okxbot.common.response.ApiResult;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ObjectMapper objectMapper;
    private final AuthProperties authProperties;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                // 知识库 PDF 等同源 iframe 预览需要 sameOrigin（默认 DENY 会浏览器拒绝）
                .headers(h -> h.frameOptions(f -> f.sameOrigin()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // SSE/SseEmitter 异步派发时 SecurityContext 不在 HTTP 线程，勿二次鉴权
                        // （否则响应已提交后抛 AccessDenied → dispatcherServlet ERROR 日志）
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/register/**",
                                "/api/auth/password/**",
                                "/api/auth/wx-mini/login",
                                "/api/auth/wx-mini/bind",
                                "/api/auth/oauth/**"
                        ).permitAll()
                        // 支付异步回调 / 同步回跳：渠道无 JWT，须验签（PayNotifyController）
                        .requestMatchers(
                                "/api/pay/notify/**",
                                "/api/pay/return/**"
                        ).permitAll()
                        // 知识库公开分享阅读（无需登录）
                        .requestMatchers("/api/v1/kb/public/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        // 用户管理 / 模型配置 CRUD / 模型连通性测试 / 全局 yt-dlp Cookie：仅超级管理员
                        .requestMatchers("/api/admin/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/v1/video/model-configs", "/api/v1/video/model-configs/**")
                        .hasRole("SUPER_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/video/models/test")
                        .hasRole("SUPER_ADMIN")
                        // Cookie 状态给登录用户看（进视频提取页会查）；上传/清除仍仅超管
                        .requestMatchers(HttpMethod.POST, "/api/v1/video/cookies", "/api/v1/video/cookies/**")
                        .hasRole("SUPER_ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/video/cookies", "/api/v1/video/cookies/**")
                        .hasRole("SUPER_ADMIN")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            if (response.isCommitted()) {
                                log.debug("认证失败但响应已提交: {}", authException.getMessage());
                                return;
                            }
                            response.setStatus(401);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write(objectMapper.writeValueAsString(
                                    ApiResult.fail(401, "未登录或登录已过期")));
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            if (response.isCommitted()) {
                                // SSE 异步完成阶段常见，业务流已写出，避免二次写响应
                                log.debug("拒绝访问但响应已提交: uri={}, msg={}",
                                        request.getRequestURI(), accessDeniedException.getMessage());
                                return;
                            }
                            response.setStatus(403);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write(objectMapper.writeValueAsString(
                                    ApiResult.fail(403, "无权限")));
                        })
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = authProperties.getCors() != null
                ? authProperties.getCors().getAllowedOrigins()
                : null;
        if (origins == null || origins.isEmpty()) {
            // 开发默认：本地 SPA；生产请在 yml/env 显式配置 auth.cors.allowed-origins
            config.setAllowedOrigins(List.of(
                    "http://localhost:3000",
                    "http://127.0.0.1:3000",
                    "http://localhost:5173",
                    "http://127.0.0.1:5173",
                    "https://dwcode.cloud",
                    "https://www.dwcode.cloud"
            ));
        } else {
            config.setAllowedOrigins(origins);
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Kb-Note-Id", "X-Kb-Timing", "Content-Disposition"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
