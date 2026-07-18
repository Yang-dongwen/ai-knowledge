package com.dwcode.okxbot.pay.controller;

import com.dwcode.okxbot.pay.service.PayNotifyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;

/**
 * 支付异步通知 / 同步回跳。
 * <p>响应体为渠道协议原文，禁止依赖 GlobalExceptionHandler 的 ApiResult。
 */
@Slf4j
@RestController
@RequestMapping("/api/pay")
@RequiredArgsConstructor
public class PayNotifyController {

    private final PayNotifyService payNotifyService;

    /**
     * 支付宝异步通知：form POST，成功返回纯文本 success。
     */
    @PostMapping(value = "/notify/alipay", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> alipayNotify(HttpServletRequest request,
                                               @RequestHeader HttpHeaders headers) {
        try {
            // 支付宝验签推荐用 getParameterMap（容器已解码）；再编码成 form 供统一解析
            String raw = formFromParameterMap(request);
            if (raw == null || raw.isBlank()) {
                raw = readRawBody(request);
            }
            boolean ok = payNotifyService.handle("alipay", headers, raw);
            return ResponseEntity.ok(ok ? "success" : "failure");
        } catch (Throwable t) {
            log.error("alipay notify uncaught", t);
            return ResponseEntity.ok("failure");
        }
    }

    /**
     * 支付宝同步 return：仅引导，不履约。
     */
    @GetMapping(value = "/return/alipay", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> alipayReturn() {
        String html = """
                <!DOCTYPE html>
                <html><head><meta charset="utf-8"><title>支付结果</title></head>
                <body style="font-family:sans-serif;padding:40px;text-align:center">
                <h2>支付结果处理中</h2>
                <p>请返回网站「会员中心」查看开通状态，勿依赖本页作为成功凭证。</p>
                <p><a href="/member">返回会员中心</a></p>
                </body></html>
                """;
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    /**
     * 微信回调占位（PR6）；当前返回 FAIL，避免误配。
     */
    @PostMapping(value = "/notify/wechat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> wechatNotify() {
        return ResponseEntity.status(501)
                .body("{\"code\":\"FAIL\",\"message\":\"wechat channel not implemented\"}");
    }

    private static String readRawBody(HttpServletRequest request) {
        try {
            byte[] bytes = StreamUtils.copyToByteArray(request.getInputStream());
            if (bytes.length == 0) {
                return "";
            }
            String enc = request.getCharacterEncoding();
            return new String(bytes, enc != null ? enc : StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }

    private static String formFromParameterMap(HttpServletRequest request) {
        StringBuilder sb = new StringBuilder();
        Enumeration<String> names = request.getParameterNames();
        if (names == null) {
            return "";
        }
        boolean first = true;
        for (String name : Collections.list(names)) {
            String[] values = request.getParameterValues(name);
            if (values == null) {
                continue;
            }
            for (String v : values) {
                if (!first) {
                    sb.append('&');
                }
                first = false;
                // 参数已由容器 decode，再 encode 以便 parseFormBody 还原
                sb.append(urlEncode(name)).append('=').append(urlEncode(v == null ? "" : v));
            }
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
