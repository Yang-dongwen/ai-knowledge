package com.dwcode.okxbot.imggen.adapter.flux;

import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.chat.config.AiProperties.ProviderConfig;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.imggen.config.ImgGenProperties;
import com.dwcode.okxbot.imggen.port.ImageAsset;
import com.dwcode.okxbot.imggen.port.ImageGenCommand;
import com.dwcode.okxbot.imggen.port.ImageGenPort;
import com.dwcode.okxbot.imggen.port.ImageGenResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * NVIDIA Build FLUX 文生图（GenAI 专用端点，非 OpenAI images 协议）。
 */
@Slf4j
public class NvidiaFluxImageAdapter implements ImageGenPort {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final ImgGenProperties properties;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    public NvidiaFluxImageAdapter(ImgGenProperties properties,
                                  AiProperties aiProperties,
                                  ObjectMapper objectMapper) {
        this.properties = properties;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        int timeout = Math.max(30, properties.getFlux().getTimeoutSeconds());
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(timeout + 10L, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public ImageGenResult generate(ImageGenCommand cmd) throws Exception {
        long t0 = System.currentTimeMillis();
        String apiKey = requireApiKey(cmd.getProviderKey());
        String url = cmd.getInvokeUrl();
        if (url == null || url.isBlank()) {
            // 兼容旧 yml 回退
            url = properties.getFlux().getInvokeUrl();
        }
        if (url == null || url.isBlank()) {
            throw new BusinessException(500, "生图模型未配置 invokeUrl（请在模型管理 capability=image 中填写）");
        }

        Path outDir = cmd.getOutputsDir();
        Files.createDirectories(outDir);

        int n = Math.max(1, cmd.getN());
        long baseSeed = cmd.getSeed() != null
                ? cmd.getSeed()
                : ThreadLocalRandom.current().nextLong(0, Integer.MAX_VALUE);
        int maxSteps = 50;
        int defaultSteps = properties.getFlux().getDefaultSteps() > 0
                ? properties.getFlux().getDefaultSteps() : 28;
        // steps 已在任务创建时按库表 max 夹紧；此处再兜底
        int steps = cmd.getSteps() > 0 ? cmd.getSteps() : defaultSteps;
        steps = Math.min(maxSteps, Math.max(1, steps));

        List<ImageAsset> assets = new ArrayList<>();
        String lastRequestId = null;
        String lastRaw = null;

        int width = clampDim(cmd.getWidth(), 1024);
        int height = clampDim(cmd.getHeight(), 1024);
        String prompt = sanitizePrompt(cmd.getPrompt());

        for (int i = 1; i <= n; i++) {
            // NVIDIA 侧 seed 常用 uint32；过大或负数易触发 5xx
            long seed = (baseSeed + i - 1) & 0x7FFFFFFFL;
            Map<String, Object> body = new HashMap<>();
            body.put("prompt", prompt);
            body.put("width", width);
            body.put("height", height);
            body.put("seed", seed);
            body.put("steps", steps);

            String json = objectMapper.writeValueAsString(body);
            log.info("NVIDIA FLUX 请求: taskId={} model={} {}x{} steps={} seed={} promptLen={} ({}/{})",
                    cmd.getTaskId(), cmd.getModelId(), width, height,
                    steps, seed, prompt.length(), i, n);

            String respBody = postWithRetry(url, apiKey, json, cmd.getTaskId());
            lastRaw = respBody;
            byte[] imageBytes = extractImageBytes(respBody);
            String name = String.format("img-%02d.png", i);
            Path file = outDir.resolve(name);
            // 可能是 jpeg，先按内容魔数判断
            if (isJpeg(imageBytes)) {
                name = String.format("img-%02d.jpg", i);
                file = outDir.resolve(name);
            }
            Files.write(file, imageBytes);
            // 可选落盘原始响应（截断）
            try {
                Path providerDir = cmd.getWorkDir().resolve("provider");
                Files.createDirectories(providerDir);
                Files.writeString(providerDir.resolve(String.format("raw-%02d.json", i)),
                        truncate(respBody, 8000), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // ignore
            }

            assets.add(ImageAsset.builder()
                    .index(i)
                    .relativePath("outputs/" + name)
                    .width(width)
                    .height(height)
                    .seed(seed)
                    .build());
            lastRequestId = extractRequestId(respBody);
        }

        return ImageGenResult.builder()
                .images(assets)
                .providerLatencyMs(System.currentTimeMillis() - t0)
                .providerRequestId(lastRequestId)
                .rawMetaJson(truncate(lastRaw, 2000))
                .build();
    }

    /**
     * 对 429/5xx 做有限次退避重试（NVIDIA 偶发 Internal Server Error 较常见）。
     */
    private String postWithRetry(String url, String apiKey, String json, String taskId) throws Exception {
        int maxAttempts = 3;
        BusinessException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .post(RequestBody.create(json, JSON))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    return respBody;
                }
                int code = response.code();
                String detail = extractErrorDetail(respBody, code);
                last = new BusinessException(502, "NVIDIA FLUX 失败 HTTP " + code + ": " + detail
                        + "（taskId=" + taskId + ", attempt=" + attempt + "/" + maxAttempts + "）");
                boolean retryable = code == 429 || code == 500 || code == 502 || code == 503 || code == 504;
                if (!retryable || attempt >= maxAttempts) {
                    throw last;
                }
                long sleepMs = 800L * attempt * attempt;
                log.warn("NVIDIA FLUX 可重试错误 HTTP {}，{}ms 后重试 ({}/{}): {}",
                        code, sleepMs, attempt, maxAttempts, truncate(detail, 200));
                Thread.sleep(sleepMs);
            } catch (BusinessException e) {
                throw e;
            } catch (java.io.IOException e) {
                last = new BusinessException(502, "NVIDIA FLUX 网络异常: " + e.getMessage()
                        + "（attempt=" + attempt + "/" + maxAttempts + "）");
                if (attempt >= maxAttempts) {
                    throw last;
                }
                long sleepMs = 800L * attempt * attempt;
                log.warn("NVIDIA FLUX IO 异常，{}ms 后重试 ({}/{}): {}",
                        sleepMs, attempt, maxAttempts, e.getMessage());
                Thread.sleep(sleepMs);
            }
        }
        throw last != null ? last : new BusinessException(502, "NVIDIA FLUX 未知失败");
    }

    private String extractErrorDetail(String respBody, int code) {
        if (respBody == null || respBody.isBlank()) {
            return code == 500
                    ? "Internal Server Error（NVIDIA 侧瞬时故障，已自动重试；仍失败请稍后再试或降低 image-concurrency）"
                    : ("empty body, HTTP " + code);
        }
        String trimmed = respBody.trim();
        try {
            JsonNode root = objectMapper.readTree(trimmed);
            if (root.has("detail")) {
                JsonNode d = root.get("detail");
                if (d.isTextual()) {
                    return truncate(d.asText(), 400);
                }
                return truncate(d.toString(), 400);
            }
            if (root.has("title") || root.has("message") || root.has("error")) {
                StringBuilder sb = new StringBuilder();
                for (String f : List.of("title", "message", "error", "type")) {
                    if (root.has(f) && root.get(f).isTextual()) {
                        if (!sb.isEmpty()) {
                            sb.append(" | ");
                        }
                        sb.append(root.get(f).asText());
                    }
                }
                if (!sb.isEmpty()) {
                    return truncate(sb.toString(), 400);
                }
            }
        } catch (Exception ignored) {
            // plain text body
        }
        return truncate(trimmed, 400);
    }

    /** 宽高夹到 FLUX 常用范围，并向下对齐到 16 的倍数。 */
    static int clampDim(int value, int fallback) {
        int v = value > 0 ? value : fallback;
        v = Math.min(1440, Math.max(256, v));
        v = (v / 16) * 16;
        if (v < 256) {
            v = 256;
        }
        return v;
    }

    /** 去掉控制字符、限制长度，降低上游 5xx 概率。 */
    static String sanitizePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(400, "生图 prompt 为空");
        }
        String p = prompt.replaceAll("[\\p{Cntrl}&&[^\n\t]]", " ").trim();
        // 约 1500 字符对 FLUX 通常足够；过长有时会触发网关 500
        final int max = 1500;
        if (p.length() > max) {
            p = p.substring(0, max).trim();
        }
        return p;
    }

    private String requireApiKey(String providerKey) {
        String keyName = providerKey;
        if (keyName == null || keyName.isBlank()) {
            keyName = properties.getFlux().getProviderKey();
        }
        if (keyName == null || keyName.isBlank()) {
            keyName = "nvidia";
        }
        ProviderConfig pc = aiProperties.getProvider(keyName);
        if (pc == null || pc.getApiKey() == null || pc.getApiKey().isBlank()) {
            throw new BusinessException(400, "NVIDIA API Key 未配置，请在 ai.providers."
                    + keyName + ".api-key 填写");
        }
        return pc.getApiKey().trim();
    }

    private byte[] extractImageBytes(String respBody) throws Exception {
        JsonNode root = objectMapper.readTree(respBody);
        // artifacts[0].base64
        JsonNode artifacts = root.get("artifacts");
        if (artifacts != null && artifacts.isArray() && !artifacts.isEmpty()) {
            JsonNode b64 = artifacts.get(0).get("base64");
            if (b64 != null && !b64.asText().isBlank()) {
                return Base64.getDecoder().decode(stripDataUrl(b64.asText()));
            }
        }
        // image / b64_json / data
        for (String field : List.of("image", "b64_json", "data", "base64")) {
            JsonNode n = root.get(field);
            if (n != null && n.isTextual() && !n.asText().isBlank()) {
                return Base64.getDecoder().decode(stripDataUrl(n.asText()));
            }
        }
        // nested data.image
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
        throw new BusinessException(502, "无法从 NVIDIA 响应解析图片 base64: " + truncate(respBody, 200));
    }

    private static String stripDataUrl(String s) {
        int idx = s.indexOf("base64,");
        if (idx >= 0) {
            return s.substring(idx + "base64,".length());
        }
        return s.trim();
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes != null && bytes.length > 2
                && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8;
    }

    private String extractRequestId(String respBody) {
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

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
