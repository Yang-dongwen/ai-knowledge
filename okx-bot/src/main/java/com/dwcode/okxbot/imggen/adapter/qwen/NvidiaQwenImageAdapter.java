package com.dwcode.okxbot.imggen.adapter.qwen;

import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.imggen.adapter.nvidia.NvidiaImageHttpSupport;
import com.dwcode.okxbot.imggen.config.ImgGenProperties;
import com.dwcode.okxbot.imggen.port.ImageAsset;
import com.dwcode.okxbot.imggen.port.ImageGenCommand;
import com.dwcode.okxbot.imggen.port.ImageGenPort;
import com.dwcode.okxbot.imggen.port.ImageGenResult;
import com.dwcode.okxbot.imggen.util.ImageProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * NVIDIA Qwen-Image 文生图。
 * <p>
 * 官方 OpenAPI（Visual GenAI NIM）定义的是<strong>自托管 NIM</strong>路径，而不是 FLUX 的 genai 目录：
 * <ul>
 *   <li>{@code POST {base}/v1/images/generations} — OpenAI 兼容</li>
 *   <li>{@code POST {base}/v1/infer} — NIM 原生 ImageRequest</li>
 * </ul>
 * 文档：
 * <a href="https://docs.nvidia.com/nim/visual-genai/latest/api/qwen-image.html">Qwen-Image API</a>
 * OpenAPI：docs.../_static/_static/yaml/qwen-image.openapi.yaml
 * <p>
 * 错误示例：{@code https://ai.api.nvidia.com/v1/genai/qwen/qwen-image} → 纯文本 404 page not found
 * （该路径不属于 Qwen OpenAPI）。
 */
@Slf4j
public class NvidiaQwenImageAdapter implements ImageGenPort {

    /** 云端若开通 OpenAI Images 托管时的常见地址（不一定对所有账号开放） */
    public static final String DEFAULT_CLOUD_OPENAI_IMAGES =
            "https://ai.api.nvidia.com/v1/images/generations";
    /** 本地 NIM 默认 */
    public static final String DEFAULT_LOCAL_OPENAI_IMAGES =
            "http://127.0.0.1:8000/v1/images/generations";
    public static final String DEFAULT_LOCAL_INFER =
            "http://127.0.0.1:8000/v1/infer";

    private final ImgGenProperties properties;
    private final NvidiaImageHttpSupport http;

    public NvidiaQwenImageAdapter(ImgGenProperties properties,
                                  AiProperties aiProperties,
                                  ObjectMapper objectMapper) {
        this.properties = properties;
        this.http = new NvidiaImageHttpSupport(
                aiProperties,
                objectMapper,
                properties.getFlux().getTimeoutSeconds(),
                properties.getFlux().getProviderKey()
        );
    }

    @Override
    public ImageGenResult generate(ImageGenCommand cmd) throws Exception {
        long t0 = System.currentTimeMillis();
        String providerKey = cmd.getProviderKey() != null ? cmd.getProviderKey()
                : properties.getFlux().getProviderKey();
        String apiKey = http.requireApiKey(providerKey);

        String protocol = ImageProtocol.resolve(cmd.getProtocol(), cmd.getModelId(), cmd.getInvokeUrl());
        Endpoint ep = resolveEndpoint(cmd.getInvokeUrl(), protocol);

        Path outDir = cmd.getOutputsDir();
        Files.createDirectories(outDir);

        // OpenAPI：n/samples 仅支持 1
        int n = 1;
        long seed = cmd.getSeed() != null
                ? cmd.getSeed()
                : ThreadLocalRandom.current().nextLong(0, Integer.MAX_VALUE);
        // OpenAPI steps: min 5, max 100, default 30
        int steps = cmd.getSteps() > 0 ? cmd.getSteps() : 30;
        steps = Math.min(100, Math.max(5, steps));

        int width = snapDim(cmd.getWidth() > 0 ? cmd.getWidth() : 1024);
        int height = snapDim(cmd.getHeight() > 0 ? cmd.getHeight() : 1024);

        Map<String, Object> body = new HashMap<>();
        body.put("prompt", truncatePrompt(cmd.getPrompt(), 800));
        if (cmd.getNegativePrompt() != null && !cmd.getNegativePrompt().isBlank()) {
            body.put("negative_prompt", truncatePrompt(cmd.getNegativePrompt(), 500));
        }
        body.put("seed", seed);
        body.put("steps", steps);
        body.put("cfg_scale", 4.0);

        if (ep.mode == Mode.OPENAI_IMAGES) {
            // ImageGenerationRequest
            if (cmd.getModelId() != null && !cmd.getModelId().isBlank()) {
                body.put("model", normalizeOpenAiModelId(cmd.getModelId()));
            }
            body.put("n", 1);
            body.put("response_format", "b64_json");
            body.put("size", width + "x" + height);
        } else {
            // ImageRequest /v1/infer
            body.put("width", width);
            body.put("height", height);
            body.put("samples", 1);
        }

        String json = http.mapper().writeValueAsString(body);
        log.info("NVIDIA Qwen 请求: taskId={} mode={} url={} model={} {}x{} steps={} seed={}",
                cmd.getTaskId(), ep.mode, ep.url, cmd.getModelId(), width, height, steps, seed);

        String respBody;
        try {
            respBody = http.postJson(ep.url, apiKey, json);
        } catch (BusinessException ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : "";
            if (msg.contains("404") || msg.toLowerCase(Locale.ROOT).contains("not found")) {
                throw new BusinessException(502, build404Help(ep.url, cmd.getModelId()) + " 原始错误: " + msg);
            }
            throw ex;
        }

        http.writeRaw(cmd.getWorkDir(), 1, respBody);
        String requestId = http.extractRequestId(respBody);

        List<byte[]> images = extractAllImages(respBody);
        if (images.isEmpty()) {
            images = List.of(http.extractImageBytes(respBody));
        }

        List<ImageAsset> assets = new ArrayList<>();
        int idx = 1;
        for (byte[] imageBytes : images) {
            String name = String.format("img-%02d.png", idx);
            Path file = outDir.resolve(name);
            if (NvidiaImageHttpSupport.isJpeg(imageBytes)) {
                name = String.format("img-%02d.jpg", idx);
                file = outDir.resolve(name);
            }
            Files.write(file, imageBytes);
            assets.add(ImageAsset.builder()
                    .index(idx)
                    .relativePath("outputs/" + name)
                    .width(width)
                    .height(height)
                    .seed(seed + idx - 1)
                    .build());
            idx++;
            if (idx > n) {
                break;
            }
        }

        return ImageGenResult.builder()
                .images(assets)
                .providerLatencyMs(System.currentTimeMillis() - t0)
                .providerRequestId(requestId)
                .rawMetaJson(NvidiaImageHttpSupport.truncate(respBody, 2000))
                .build();
    }

    private enum Mode {
        OPENAI_IMAGES,
        INFER
    }

    private record Endpoint(String url, Mode mode) {
    }

    /**
     * 纠正错误的 genai 路径，并选择 OpenAI Images 或 /v1/infer。
     */
    private Endpoint resolveEndpoint(String invokeUrl, String protocol) {
        String url = invokeUrl == null ? "" : invokeUrl.trim();
        String lower = url.toLowerCase(Locale.ROOT);

        // 明确误用 FLUX 式 genai 路径
        if (lower.contains("/v1/genai/qwen") || lower.contains("/genai/qwen/")) {
            log.warn("检测到错误的 Qwen GenAI 目录 URL（会 404），改用 OpenAI Images 兼容路径: {}", url);
            url = DEFAULT_CLOUD_OPENAI_IMAGES;
            lower = url.toLowerCase(Locale.ROOT);
        }

        if (url.isBlank()) {
            // 默认走云端 Images；若账号未开通托管，需改成本地 NIM
            url = DEFAULT_CLOUD_OPENAI_IMAGES;
            lower = url;
            log.info("Qwen 未配置 invokeUrl，默认 {}", url);
        }

        if (ImageProtocol.NVIDIA_QWEN_INFER.equals(protocol)
                || lower.endsWith("/v1/infer") || lower.contains("/v1/infer?")) {
            if (!lower.contains("/v1/infer")) {
                url = joinBase(url, "/v1/infer");
            }
            return new Endpoint(url, Mode.INFER);
        }

        // nvidia-qwen / nvidia-openai-images / 默认 → OpenAI Images
        if (!lower.contains("/v1/images/generations") && !lower.contains("/images/generations")) {
            // 若用户只填了 base（如 http://127.0.0.1:8000）则拼接
            if (looksLikeBaseOnly(url)) {
                url = joinBase(url, "/v1/images/generations");
            }
        }
        return new Endpoint(url, Mode.OPENAI_IMAGES);
    }

    private static boolean looksLikeBaseOnly(String url) {
        String u = url;
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return !u.contains("/v1/");
    }

    private static String joinBase(String base, String path) {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return b + path;
    }

    private static String normalizeOpenAiModelId(String modelId) {
        // OpenAPI 示例常用 black-forest-labs/flux.1-dev；Build 上 Qwen 常见 qwen/qwen-image
        return modelId.trim();
    }

    /** 宽高对齐到 16 的倍数并限制在 512~1664（OpenAPI 枚举步进 16） */
    private static int snapDim(int v) {
        int x = Math.min(1664, Math.max(512, v));
        int snapped = (x / 16) * 16;
        if (snapped < 512) {
            snapped = 512;
        }
        return snapped;
    }

    private static String truncatePrompt(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String build404Help(String url, String modelId) {
        return "Qwen 生图 404（路径不存在或账号未开通该托管接口）。"
                + " 官方文档 https://docs.nvidia.com/nim/visual-genai/latest/api/qwen-image.html "
                + "定义的是自托管 NIM：POST {base}/v1/images/generations 或 POST {base}/v1/infer，"
                + "不是 FLUX 的 /v1/genai/qwen/... 。"
                + " 请将模型 invoke_url 改为本地 NIM 地址，例如 http://127.0.0.1:8000/v1/images/generations "
                + "（并部署 qwen-image 容器），或确认云端是否提供 Images 托管。"
                + " 当前 URL=" + url + " model=" + modelId + "。";
    }

    private List<byte[]> extractAllImages(String respBody) throws Exception {
        List<byte[]> out = new ArrayList<>();
        var root = http.mapper().readTree(respBody);
        // OpenAI: data[].b64_json
        var data = root.get("data");
        if (data != null && data.isArray()) {
            for (var node : data) {
                for (String field : List.of("b64_json", "base64", "image")) {
                    var n = node.get(field);
                    if (n != null && n.isTextual() && !n.asText().isBlank()) {
                        out.add(java.util.Base64.getDecoder().decode(
                                NvidiaImageHttpSupport.stripDataUrl(n.asText())));
                        break;
                    }
                }
            }
        }
        // Infer: artifacts[].base64
        var artifacts = root.get("artifacts");
        if (artifacts != null && artifacts.isArray()) {
            for (var node : artifacts) {
                var b64 = node.get("base64");
                if (b64 != null && b64.isTextual() && !b64.asText().isBlank()) {
                    out.add(java.util.Base64.getDecoder().decode(
                            NvidiaImageHttpSupport.stripDataUrl(b64.asText())));
                }
            }
        }
        return out;
    }
}
