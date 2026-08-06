package com.dwcode.okxbot.auth.oauth;

import com.dwcode.okxbot.auth.dto.LoginResponse;
import com.dwcode.okxbot.auth.dto.OAuthExchangeRequest;
import com.dwcode.okxbot.auth.dto.OAuthProvidersResponse;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.common.response.ApiResult;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * PC 端 Google / GitHub OAuth：authorize → callback → ticket exchange。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth/oauth")
@RequiredArgsConstructor
public class OAuthController {

    private final OAuthService oAuthService;

    @GetMapping("/providers")
    public ApiResult<OAuthProvidersResponse> providers() {
        return ApiResult.ok(oAuthService.listProviders());
    }

    /**
     * 浏览器整页跳转入口。成功 302 到 Google/GitHub（或 mock callback）。
     */
    @GetMapping("/{provider}/authorize")
    public void authorize(
            @PathVariable String provider,
            @RequestParam(value = "redirect", required = false) String redirect,
            HttpServletResponse response
    ) throws IOException {
        try {
            OAuthProvider p = OAuthProvider.fromPath(provider);
            String url = oAuthService.buildAuthorizeRedirect(p, redirect);
            response.sendRedirect(url);
        } catch (BusinessException e) {
            log.warn("oauth authorize failed: {}", e.getMessage());
            response.sendRedirect(oAuthService.buildErrorRedirect("authorize_failed"));
        } catch (Exception e) {
            log.error("oauth authorize error", e);
            response.sendRedirect(oAuthService.buildErrorRedirect("authorize_failed"));
        }
    }

    /**
     * 平台回调。成功 302 到前端 /oauth/callback?ticket=…；失败带 oauth_error。
     */
    @GetMapping("/{provider}/callback")
    public void callback(
            @PathVariable String provider,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            HttpServletResponse response
    ) throws IOException {
        try {
            if (error != null && !error.isBlank()) {
                log.warn("oauth provider returned error provider={} error={}", provider, error);
                response.sendRedirect(oAuthService.buildErrorRedirect("provider_denied"));
                return;
            }
            OAuthProvider p = OAuthProvider.fromPath(provider);
            String url = oAuthService.handleCallback(p, code, state);
            response.sendRedirect(url);
        } catch (BusinessException e) {
            log.warn("oauth callback business error: {}", e.getMessage());
            String codeHint = "callback_failed";
            if (e.getMessage() != null) {
                if (e.getMessage().contains("邮箱")) {
                    codeHint = "email_required";
                } else if (e.getMessage().contains("代理") || e.getMessage().contains("网络")
                        || e.getMessage().contains("无法访问")) {
                    codeHint = "network_failed";
                }
            }
            response.sendRedirect(oAuthService.buildErrorRedirect(codeHint));
        } catch (Exception e) {
            log.error("oauth callback error", e);
            response.sendRedirect(oAuthService.buildErrorRedirect("callback_failed"));
        }
    }

    /**
     * 前端用 one-time ticket 换正式 JWT（与 /api/auth/login 响应结构一致）。
     */
    @PostMapping("/exchange")
    public ApiResult<LoginResponse> exchange(@Valid @RequestBody OAuthExchangeRequest request) {
        return ApiResult.ok(oAuthService.exchangeTicket(request.getTicket()));
    }
}
