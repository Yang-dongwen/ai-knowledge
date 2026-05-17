package com.dwcode.okxbot.okx.client;

import com.dwcode.okxbot.common.exception.OkxApiException;
import com.dwcode.okxbot.okx.auth.OkxSigner;
import com.dwcode.okxbot.okx.config.OkxProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;

/**
 * OKX REST API 客户端。
 *
 * 职责：
 * 1. 统一处理 OKX 请求签名
 * 2. 统一处理请求头
 * 3. 统一处理响应解析
 * 4. 统一处理错误
 */
@Slf4j
@Component
public class OkxRestClient {

    private final OkxProperties properties;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OkxRestClient(OkxProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(properties.getProxyHost(), properties.getProxyPort()));
        this.httpClient = new OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(properties.getTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(properties.getTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(properties.getTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
    }

    /**
     * 发送 GET 请求（私有接口）。
     */
    public JsonNode get(String requestPath, String apiKey, String secretKey, String passphrase) {
        String timestamp = OkxSigner.generateTimestamp();
        String sign = OkxSigner.sign(timestamp, "GET", requestPath, "", secretKey);
        log.debug("GET签名诊断: timestamp={}, requestPath={}, sign={}", timestamp, requestPath, sign);

        Request request = new Request.Builder()
                .url(properties.getBaseUrl() + requestPath)
                .get()
                .headers(buildHeaders(apiKey, sign, timestamp, passphrase))
                .build();

        return executeRequest(request);
    }

    /**
     * 发送 POST 请求（私有接口）。
     */
    public JsonNode post(String requestPath, String body, String apiKey, String secretKey, String passphrase) {
        String timestamp = OkxSigner.generateTimestamp();
        String sign = OkxSigner.sign(timestamp, "POST", requestPath, body, secretKey);

        RequestBody requestBody = RequestBody.create(body, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(properties.getBaseUrl() + requestPath)
                .post(requestBody)
                .headers(buildHeaders(apiKey, sign, timestamp, passphrase))
                .build();

        return executeRequest(request);
    }

    /**
     * 发送 GET 请求（公共接口，无需签名）。
     */
    public JsonNode getPublic(String requestPath) {
        Request.Builder builder = new Request.Builder()
                .url(properties.getBaseUrl() + requestPath)
                .get();

        if (properties.isSimulated()) {
            builder.addHeader("x-simulated-trading", "1");
        }

        return executeRequest(builder.build());
    }

    private Headers buildHeaders(String apiKey, String sign, String timestamp, String passphrase) {
        Headers.Builder builder = new Headers.Builder()
                .add("OK-ACCESS-KEY", apiKey)
                .add("OK-ACCESS-SIGN", sign)
                .add("OK-ACCESS-TIMESTAMP", timestamp)
                .add("OK-ACCESS-PASSPHRASE", passphrase)
                .add("Content-Type", "application/json");

        if (properties.isSimulated()) {
            builder.add("x-simulated-trading", "1");
        }

        return builder.build();
    }

    private JsonNode executeRequest(Request request) {
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("OKX请求失败: url={}, httpCode={}, body={}", request.url(), response.code(), responseBody);
                // 尝试解析OKX错误信息
                try {
                    JsonNode errJson = objectMapper.readTree(responseBody);
                    String errCode = errJson.path("code").asText("");
                    String errMsg = errJson.path("msg").asText("");
                    if (!errCode.isEmpty()) {
                        log.error("OKX错误详情: code={}, msg={}", errCode, errMsg);
                    }
                } catch (Exception ignored) {}
                throw new OkxApiException(String.valueOf(response.code()), "OKX请求失败: HTTP " + response.code() + " body=" + responseBody, responseBody);
            }

            JsonNode jsonNode = objectMapper.readTree(responseBody);

            // OKX 返回 code != "0" 表示业务错误
            String code = jsonNode.path("code").asText("0");
            if (!"0".equals(code)) {
                String msg = jsonNode.path("msg").asText("未知错误");
                log.error("OKX业务错误: url={}, code={}, msg={}", request.url(), code, msg);
                throw new OkxApiException(code, msg, responseBody);
            }

            return jsonNode;
        } catch (OkxApiException e) {
            throw e;
        } catch (IOException e) {
            log.error("OKX请求IO异常: url={}", request.url(), e);
            throw new OkxApiException("OKX请求超时或网络异常: " + e.getMessage());
        }
    }
}
