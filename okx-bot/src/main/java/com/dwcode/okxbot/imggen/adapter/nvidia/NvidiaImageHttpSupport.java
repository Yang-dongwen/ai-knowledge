package com.dwcode.okxbot.imggen.adapter.nvidia;

import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.chat.config.AiProperties.ProviderConfig;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * NVIDIA 生图 HTTP / 鉴权 / base64 解析公共逻辑。
 */
public final class NvidiaImageHttpSupport {

    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;
    private final String defaultProviderKey;

    public NvidiaImageHttpSupport(AiProperties aiProperties,
                                  ObjectMapper objectMapper,
                                  int timeoutSeconds,
                                  String defaultProviderKey) {
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        this.defaultProviderKey = defaultProviderKey != null && !defaultProviderKey.isBlank()
                ? defaultProviderKey : "nvidia";
        int timeout = Math.max(30, timeoutSeconds);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(timeout + 15L, TimeUnit.SECONDS)
                .build();
    }

    public String requireApiKey(String providerKey) {
        String keyName = providerKey;
        if (keyName == null || keyName.isBlank()) {
            keyName = defaultProviderKey;
        }
        ProviderConfig pc = aiProperties.getProvider(keyName);
        if (pc == null || pc.getApiKey() == null || pc.getApiKey().isBlank()) {
            throw new BusinessException(400, "API Key 未配置，请在 ai.providers."
                    + keyName + ".api-key 填写");
        }
        return pc.getApiKey().trim();
    }

    public String postJson(String url, String apiKey, String jsonBody) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .post(RequestBody.create(jsonBody, JSON))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new BusinessException(502, "生图 HTTP " + response.code() + ": "
                        + truncate(respBody, 500));
            }
            return respBody;
        }
    }

    public byte[] extractImageBytes(String respBody) throws Exception {
        JsonNode root = objectMapper.readTree(respBody);
        JsonNode artifacts = root.get("artifacts");
        if (artifacts != null && artifacts.isArray() && !artifacts.isEmpty()) {
            JsonNode b64 = artifacts.get(0).get("base64");
            if (b64 != null && !b64.asText().isBlank()) {
                return Base64.getDecoder().decode(stripDataUrl(b64.asText()));
            }
        }
        for (String field : List.of("image", "b64_json", "data", "base64")) {
            JsonNode n = root.get(field);
            if (n != null && n.isTextual() && !n.asText().isBlank()) {
                return Base64.getDecoder().decode(stripDataUrl(n.asText()));
            }
        }
        JsonNode data = root.get("data");
        if (data != null) {
            if (data.isArray() && !data.isEmpty()) {
                JsonNode first = data.get(0);
                for (String field : List.of("b64_json", "base64", "image")) {
                    JsonNode n = first.get(field);
                    if (n != null && n.isTextual() && !n.asText().isBlank()) {
                        return Base64.getDecoder().decode(stripDataUrl(n.asText()));
                    }
                }
            } else if (data.isObject()) {
                for (String field : List.of("b64_json", "base64", "image")) {
                    JsonNode n = data.get(field);
                    if (n != null && n.isTextual() && !n.asText().isBlank()) {
                        return Base64.getDecoder().decode(stripDataUrl(n.asText()));
                    }
                }
            }
        }
        throw new BusinessException(502, "无法从响应解析图片 base64: " + truncate(respBody, 220));
    }

    public void writeRaw(Path workDir, int index, String respBody) {
        if (workDir == null) {
            return;
        }
        try {
            Path dir = workDir.resolve("provider");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(String.format("raw-%02d.json", index)),
                    truncate(respBody, 8000), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // ignore
        }
    }

    public String extractRequestId(String respBody) {
        try {
            JsonNode root = objectMapper.readTree(respBody);
            for (String f : List.of("id", "requestId", "request_id")) {
                if (root.has(f) && root.get(f).isTextual()) {
                    return root.get(f).asText();
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    public ObjectMapper mapper() {
        return objectMapper;
    }

    public static boolean isJpeg(byte[] bytes) {
        return bytes != null && bytes.length > 2
                && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8;
    }

    public static String stripDataUrl(String s) {
        int idx = s.indexOf("base64,");
        if (idx >= 0) {
            return s.substring(idx + "base64,".length());
        }
        return s.trim();
    }

    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public static String sizeLabel(int width, int height) {
        return width + "x" + height;
    }
}
