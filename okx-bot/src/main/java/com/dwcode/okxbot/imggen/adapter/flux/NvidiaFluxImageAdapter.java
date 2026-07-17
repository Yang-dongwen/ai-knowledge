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

        for (int i = 1; i <= n; i++) {
            long seed = (baseSeed + i - 1) & 0xFFFFFFFFL;
            Map<String, Object> body = new HashMap<>();
            body.put("prompt", cmd.getPrompt());
            body.put("width", cmd.getWidth());
            body.put("height", cmd.getHeight());
            body.put("seed", seed);
            body.put("steps", steps);

            String json = objectMapper.writeValueAsString(body);
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .post(RequestBody.create(json, JSON))
                    .build();

            log.info("NVIDIA FLUX 请求: taskId={} model={} {}x{} steps={} seed={} ({}/{})",
                    cmd.getTaskId(), cmd.getModelId(), cmd.getWidth(), cmd.getHeight(),
                    steps, seed, i, n);

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                lastRaw = respBody;
                if (!response.isSuccessful()) {
                    throw new BusinessException(502, "NVIDIA FLUX 失败 HTTP "
                            + response.code() + ": " + truncate(respBody, 400));
                }
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
                    Files.writeString(cmd.getWorkDir().resolve("provider")
                                    .resolve(String.format("raw-%02d.json", i)),
                            truncate(respBody, 8000), StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    // ignore
                }

                assets.add(ImageAsset.builder()
                        .index(i)
                        .relativePath("outputs/" + name)
                        .width(cmd.getWidth())
                        .height(cmd.getHeight())
                        .seed(seed)
                        .build());
                lastRequestId = extractRequestId(respBody);
            }
        }

        return ImageGenResult.builder()
                .images(assets)
                .providerLatencyMs(System.currentTimeMillis() - t0)
                .providerRequestId(lastRequestId)
                .rawMetaJson(truncate(lastRaw, 2000))
                .build();
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
