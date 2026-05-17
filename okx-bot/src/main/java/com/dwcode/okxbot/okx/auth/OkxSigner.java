package com.dwcode.okxbot.okx.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * OKX API 签名工具。
 *
 * 签名规则：
 * 1. 拼接 timestamp + method + requestPath + body
 * 2. 使用 HMAC-SHA256 + SecretKey 签名
 * 3. Base64 编码
 */
public class OkxSigner {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    /**
     * 生成 ISO 格式时间戳。
     */
    public static String generateTimestamp() {
        return Instant.now().atZone(ZoneOffset.UTC).format(TIMESTAMP_FORMATTER);
    }

    /**
     * 生成 OKX API 签名。
     *
     * @param timestamp   ISO 时间戳
     * @param method      HTTP 方法 GET/POST
     * @param requestPath 请求路径（含查询参数）
     * @param body        请求体（GET 请求为空字符串）
     * @param secretKey   Secret Key
     * @return Base64 编码的签名
     */
    public static String sign(String timestamp, String method, String requestPath, String body, String secretKey) {
        String preSign = timestamp + method.toUpperCase() + requestPath + (body == null ? "" : body);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(preSign.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("OKX签名失败", e);
        }
    }
}
