package com.dwcode.okxbot.auth.mail;

import com.dwcode.okxbot.auth.config.AuthProperties;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AgentMail HTTP 发件客户端。
 * <p>
 * API: POST {baseUrl}/v0/inboxes/{inboxId}/messages/send
 * 文档: https://docs.agentmail.to/api-reference/inboxes/messages/send
 */
@Slf4j
@Component
public class AgentMailClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    /**
     * Spring 注入用。类内另有三参构造供单测，必须显式 {@link Autowired} 标明入口，
     * 否则多构造函数时 Spring 会报 No default constructor found。
     */
    @Autowired
    public AgentMailClient(AuthProperties authProperties, ObjectMapper objectMapper) {
        this(authProperties, objectMapper, buildHttpClient(authProperties));
    }

    /** 供单测注入自定义 HTTP 客户端 */
    AgentMailClient(AuthProperties authProperties, ObjectMapper objectMapper, OkHttpClient httpClient) {
        this.authProperties = authProperties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    static OkHttpClient buildHttpClient(AuthProperties authProperties) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(30));

        AuthProperties.Mail.AgentMail cfg = authProperties.getMail().getAgentmail();
        String host = cfg.getProxyHost() == null ? "" : cfg.getProxyHost().trim();
        int port = cfg.getProxyPort();
        if (!host.isEmpty() && port > 0) {
            Proxy.Type type = "SOCKS".equalsIgnoreCase(trim(cfg.getProxyType()))
                    ? Proxy.Type.SOCKS
                    : Proxy.Type.HTTP;
            Proxy proxy = new Proxy(type, new InetSocketAddress(host, port));
            builder.proxy(proxy);
            log.info("AgentMail HTTP 客户端已配置代理: type={} {}:{}", type, host, port);
        } else {
            log.info("AgentMail HTTP 客户端未配置代理（直连）；本机 VPN 需设置 auth.mail.agentmail.proxy-host/port");
        }
        return builder.build();
    }

    public void sendText(String to, String subject, String text) {
        AuthProperties.Mail.AgentMail cfg = authProperties.getMail().getAgentmail();
        String apiKey = trim(cfg.getApiKey());
        String inboxId = trim(cfg.getInboxId());
        if (apiKey.isEmpty() || inboxId.isEmpty()) {
            throw new BusinessException("AgentMail 未配置：请设置 auth.mail.agentmail.api-key 与 inbox-id");
        }

        String baseUrl = trim(cfg.getBaseUrl());
        if (baseUrl.isEmpty()) {
            baseUrl = "https://api.agentmail.to";
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String url = baseUrl + "/v0/inboxes/" + encodePath(inboxId) + "/messages/send";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", to);
        body.put("subject", subject);
        body.put("text", text);
        body.put("labels", java.util.List.of("auth", "verification-code"));

        try {
            String json = objectMapper.writeValueAsString(body);
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "okx-bot/1.0 (AgentMail)")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .post(RequestBody.create(json, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("AgentMail 发信失败: status={} bodySnippet={}",
                            response.code(), snippet(respBody, 300));
                    throw new BusinessException(mapHttpError(response.code(), respBody, objectMapper));
                }
                String messageId = extractField(respBody, "message_id");
                log.info("AgentMail 邮件已发送: to={}, subject={}, messageId={}", to, subject, messageId);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            log.error("AgentMail 发信网络异常: {}", e.getMessage());
            throw new BusinessException("邮件发送失败：无法连接 AgentMail，请检查网络/代理（国内可能被 CloudFront 拦截）");
        } catch (Exception e) {
            log.error("AgentMail 发信异常: {}", e.getMessage());
            throw new BusinessException("邮件发送失败，请稍后重试");
        }
    }

    /**
     * 区分：CloudFront/WAF 拦截 vs AgentMail 业务 403 vs 其它。
     */
    static String mapHttpError(int status, String respBody, ObjectMapper mapper) {
        if (isCloudFrontBlock(respBody)) {
            return "邮件发送失败：请求被 CloudFront 拦截（status=" + status
                    + "）。常见原因是运行环境网络无法直连 api.agentmail.to（国内本机常见）。"
                    + "本机请配置 auth.mail.agentmail.proxy-host/port（Clash 如 127.0.0.1:7897），"
                    + "或将后端部署到可访问区域 / 改用 smtp。";
        }
        String detail = extractErrorMessage(respBody, mapper);
        if (status == 401 || status == 403) {
            return "邮件发送失败：AgentMail 鉴权/权限拒绝"
                    + (detail.isEmpty() ? "" : "（" + detail + "）")
                    + "。请检查 AGENTMAIL_API_KEY 与 AGENTMAIL_INBOX_ID 是否匹配且有 send 权限。";
        }
        if (status == 404) {
            return "邮件发送失败：Inbox 不存在或 base-url/路径错误"
                    + (detail.isEmpty() ? "" : "（" + detail + "）");
        }
        return "邮件发送失败，请稍后重试"
                + (detail.isEmpty() ? "（HTTP " + status + "）" : "（" + detail + "）");
    }

    static boolean isCloudFrontBlock(String respBody) {
        if (respBody == null || respBody.isBlank()) {
            return false;
        }
        String lower = respBody.toLowerCase();
        return lower.contains("generated by cloudfront")
                || lower.contains("request blocked")
                || (lower.contains("<html") && lower.contains("403 error"));
    }

    private static String extractErrorMessage(String respBody, ObjectMapper mapper) {
        if (respBody == null || respBody.isBlank() || respBody.charAt(0) == '<') {
            return "";
        }
        try {
            JsonNode node = mapper.readTree(respBody);
            if (node.hasNonNull("message")) {
                return node.get("message").asText();
            }
            if (node.hasNonNull("code")) {
                return node.get("code").asText();
            }
        } catch (Exception ignored) {
            // ignore parse errors
        }
        return snippet(respBody, 80);
    }

    private static String snippet(String s, int max) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > max ? oneLine.substring(0, max) + "..." : oneLine;
    }

    private String extractField(String respBody, String field) {
        if (respBody == null || respBody.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(respBody);
            return node.hasNonNull(field) ? node.get(field).asText() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** inbox_id 通常为安全字符；仅做最小 path 编码避免注入 */
    private static String encodePath(String inboxId) {
        return inboxId.replace("/", "").replace("..", "");
    }
}
