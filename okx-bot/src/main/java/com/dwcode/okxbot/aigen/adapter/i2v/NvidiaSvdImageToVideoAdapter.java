package com.dwcode.okxbot.aigen.adapter.i2v;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.domain.shot.ShotDto;
import com.dwcode.okxbot.aigen.port.ImageToVideoCommand;
import com.dwcode.okxbot.aigen.port.ImageToVideoPort;
import com.dwcode.okxbot.aigen.port.ImageToVideoResult;
import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.chat.config.AiProperties.ProviderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * NVIDIA Stable Video Diffusion 图生视频（可选）。
 * <p>
 * 账号未开通或接口变更时会失败，由 {@link CompositeImageToVideoAdapter} 回退 kinetic。
 * 请求体按 GenAI 常见字段组装；若返回非预期结构则记 error。
 */
@Slf4j
public class NvidiaSvdImageToVideoAdapter implements ImageToVideoPort {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final AigenProperties aigenProperties;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    public NvidiaSvdImageToVideoAdapter(AigenProperties aigenProperties,
                                        AiProperties aiProperties,
                                        ObjectMapper objectMapper) {
        this.aigenProperties = aigenProperties;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        int timeout = Math.max(60, aigenProperties.getVisual().getSvdTimeoutSeconds());
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .callTimeout(timeout + 30L, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public ImageToVideoResult convert(ImageToVideoCommand command) {
        long t0 = System.currentTimeMillis();
        try {
            if (command == null || command.getStillImage() == null
                    || !Files.isRegularFile(command.getStillImage())) {
                return fail("stillImage 无效", t0);
            }
            long size = Files.size(command.getStillImage());
            // NVIDIA 文档：小图可直接 base64；过大则易失败
            if (size > 900 * 1024) {
                return fail("图片过大（>900KB），SVD 直传可能失败，请用 kinetic 或压缩", t0);
            }

            String providerKey = command.getProviderKey();
            if (providerKey == null || providerKey.isBlank()) {
                providerKey = aigenProperties.getVisual().getImageProviderKey();
            }
            if (providerKey == null || providerKey.isBlank()) {
                providerKey = "nvidia";
            }
            ProviderConfig pc = aiProperties.getProvider(providerKey);
            if (pc == null || pc.getApiKey() == null || pc.getApiKey().isBlank()) {
                return fail("供应商 api-key 未配置: " + providerKey, t0);
            }

            String url = aigenProperties.getVisual().getSvdInvokeUrl();
            if (url == null || url.isBlank()) {
                return fail("svdInvokeUrl 为空", t0);
            }

            byte[] bytes = Files.readAllBytes(command.getStillImage());
            String b64 = Base64.getEncoder().encodeToString(bytes);
            String mime = guessMime(command.getStillImage());

            Map<String, Object> body = new HashMap<>();
            // 兼容常见 GenAI 字段命名
            body.put("image", "data:" + mime + ";base64," + b64);
            body.put("seed", seedOf(command.getShot(), command.getSeedIndex()));
            body.put("cfg_scale", 1.8);
            body.put("motion_bucket_id", aigenProperties.getVisual().getSvdMotionBucketId());
            body.put("frames_per_second", aigenProperties.getVisual().getSvdFps());

            String json = objectMapper.writeValueAsString(body);
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + pc.getApiKey())
                    .header("Accept", "application/json")
                    .post(RequestBody.create(json, JSON))
                    .build();

            log.info("NVIDIA SVD 请求: shot={} url={} bytes={}",
                    command.getShot() != null ? command.getShot().getId() : "?",
                    url, size);

            try (Response response = httpClient.newCall(request).execute()) {
                String resp = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    return fail("HTTP " + response.code() + ": " + truncate(resp, 280), t0);
                }
                byte[] video = extractVideoBytes(resp);
                if (video == null || video.length < 512) {
                    return fail("响应无有效视频: " + truncate(resp, 200), t0);
                }

                String shotId = command.getShot() != null && command.getShot().getId() != null
                        ? command.getShot().getId()
                        : ("shot-" + command.getSeedIndex());
                Path outDir = command.getWorkDir().resolve("assets").resolve("visual");
                Files.createDirectories(outDir);
                Path out = outDir.resolve(shotId + "-svd.mp4");
                Files.write(out, video);
                String rel = "assets/visual/" + shotId + "-svd.mp4";
                log.info("NVIDIA SVD 完成: {} {}ms", rel, System.currentTimeMillis() - t0);
                return ImageToVideoResult.builder()
                        .relativePath(rel)
                        .provider("nvidia-svd")
                        .latencyMs(System.currentTimeMillis() - t0)
                        .build();
            }
        } catch (Exception e) {
            log.warn("NVIDIA SVD 异常: {}", e.getMessage());
            return fail(e.getMessage(), t0);
        }
    }

    private byte[] extractVideoBytes(String respBody) throws Exception {
        JsonNode root = objectMapper.readTree(respBody);
        // 常见：video / video_base64 / artifacts[0].base64 / output
        String[] keys = {"video", "video_base64", "mp4", "output", "b64_json"};
        for (String k : keys) {
            JsonNode n = root.get(k);
            if (n != null && n.isTextual() && !n.asText().isBlank()) {
                return decodeMaybeDataUrl(n.asText());
            }
        }
        JsonNode arts = root.get("artifacts");
        if (arts != null && arts.isArray() && !arts.isEmpty()) {
            JsonNode a0 = arts.get(0);
            if (a0.has("base64")) {
                return Base64.getDecoder().decode(a0.get("base64").asText());
            }
            if (a0.has("video")) {
                return decodeMaybeDataUrl(a0.get("video").asText());
            }
        }
        // 整包就是 base64？
        if (respBody.length() > 1000 && !respBody.trim().startsWith("{")) {
            try {
                return Base64.getDecoder().decode(respBody.trim());
            } catch (Exception ignored) {
                // fallthrough
            }
        }
        return null;
    }

    private static byte[] decodeMaybeDataUrl(String s) {
        String raw = s;
        int idx = s.indexOf("base64,");
        if (idx >= 0) {
            raw = s.substring(idx + "base64,".length());
        }
        return Base64.getDecoder().decode(raw);
    }

    private static String guessMime(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) {
            return "image/png";
        }
        if (n.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private static long seedOf(ShotDto shot, int seedIndex) {
        if (shot != null && shot.getVisual() != null && shot.getVisual().getSeed() != null) {
            return shot.getVisual().getSeed();
        }
        return seedIndex * 97L + 42L;
    }

    private static ImageToVideoResult fail(String msg, long t0) {
        return ImageToVideoResult.builder()
                .provider("nvidia-svd")
                .errorMessage(msg)
                .latencyMs(System.currentTimeMillis() - t0)
                .build();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
