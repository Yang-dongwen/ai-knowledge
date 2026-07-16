package com.dwcode.okxbot.video.adapter;

import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.chat.config.AiProperties.ProviderConfig;
import com.dwcode.okxbot.common.ai.LlmContentHelper;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.port.VisualUnderstandingResult.ChunkUnderstanding;
import com.dwcode.okxbot.video.port.VisualUnderstandingResult.OnScreenTextItem;
import com.dwcode.okxbot.video.port.VisualUnderstandingResult.SceneItem;
import com.dwcode.okxbot.video.port.VisualUnderstandingResult.VisualKeyPointItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 抽帧 + 多图 VLM（OpenAI 兼容 image_url），作为 Omni 媒体失败后备。
 */
@Slf4j
@Component
public class FrameSampleVlmAdapter {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final VideoProperties videoProperties;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    public FrameSampleVlmAdapter(VideoProperties videoProperties,
                                 AiProperties aiProperties,
                                 ObjectMapper objectMapper) {
        this.videoProperties = videoProperties;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        VideoProperties.Understanding u = videoProperties.getUnderstanding();
        int read = Math.max(60, u.getTimeoutSeconds());
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(Math.max(30, u.getWriteTimeoutSeconds()), TimeUnit.SECONDS)
                .readTimeout(read, TimeUnit.SECONDS)
                .callTimeout(read + 60L, TimeUnit.SECONDS)
                .build();
    }

    public ChunkUnderstanding understandFrames(List<Path> frames,
                                               double chunkStartSec,
                                               double chunkEndSec,
                                               String language,
                                               String providerKey,
                                               String modelId,
                                               String asrWindowText) throws Exception {
        if (frames == null || frames.isEmpty()) {
            throw new BusinessException("[FRAME] 无帧可分析");
        }
        String apiKey = requireApiKey(providerKey);
        ProviderConfig pc = aiProperties.getProvider(providerKey);
        String baseUrl = pc.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://integrate.api.nvidia.com/v1";
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String model = (modelId != null && !modelId.isBlank())
                ? modelId
                : videoProperties.getUnderstanding().getModel();

        List<Map<String, Object>> content = new ArrayList<>();
        String lang = (language != null && language.toLowerCase().startsWith("en"))
                ? "Respond in English." : "请使用中文。";
        StringBuilder text = new StringBuilder();
        text.append(lang).append(" 以下为视频均匀抽帧，按时间顺序输出 JSON：\n");
        text.append("绝对时间窗 ").append(chunkStartSec).append("~").append(chunkEndSec).append("s\n");
        text.append("""
                {
                  "overallVisualSummary": "...",
                  "scenes": [{"relativeStartSec":0,"relativeEndSec":5,"description":"..."}],
                  "onScreenTexts": [{"relativeSec":1,"text":"..."}],
                  "visualKeyPoints": [{"relativeSec":2,"point":"...","source":"visual"}]
                }
                """);
        if (asrWindowText != null && !asrWindowText.isBlank()
                && videoProperties.getUnderstanding().isFrameIncludeAsrWindowText()) {
            text.append("口播参考:\n")
                    .append(asrWindowText.length() > 1500 ? asrWindowText.substring(0, 1500) : asrWindowText);
        }
        content.add(Map.of("type", "text", "text", text.toString()));

        int maxImg = Math.max(1, videoProperties.getUnderstanding().getFrameMaxImagesPerCall());
        int n = Math.min(frames.size(), maxImg);
        for (int i = 0; i < n; i++) {
            byte[] bytes = Files.readAllBytes(frames.get(i));
            String b64 = Base64.getEncoder().encodeToString(bytes);
            Map<String, Object> img = new HashMap<>();
            img.put("type", "image_url");
            img.put("image_url", Map.of("url", "data:image/jpeg;base64," + b64));
            content.add(img);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("temperature", 0.2);
        body.put("max_tokens", videoProperties.getUnderstanding().getMapMaxTokens());
        body.put("messages", List.of(
                Map.of("role", "system", "content", "你是视频画面分析专家，只输出合法 JSON。"),
                Map.of("role", "user", "content", content)
        ));

        String json = objectMapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .post(RequestBody.create(json, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                String snip = respBody.length() > 400 ? respBody.substring(0, 400) : respBody;
                throw new BusinessException("[FRAME] HTTP " + response.code() + ": " + snip);
            }
            return parseLikeOmni(respBody, chunkStartSec, chunkEndSec);
        }
    }

    private ChunkUnderstanding parseLikeOmni(String respBody, double chunkStartSec, double chunkEndSec)
            throws Exception {
        JsonNode root = objectMapper.readTree(respBody);
        JsonNode message = root.path("choices").path(0).path("message");
        String text = firstNonBlank(
                message.path("content").asText(null),
                message.path("reasoning_content").asText(null),
                message.path("reasoning").asText(null)
        );
        if (text == null || text.isBlank()) {
            throw new BusinessException("[FRAME] empty content");
        }
        JsonNode node = LlmContentHelper.parseJsonLenient(text);
        ChunkUnderstanding ch = new ChunkUnderstanding();
        ch.setChunkStartSec(chunkStartSec);
        ch.setChunkEndSec(chunkEndSec);
        ch.setOverallVisualSummary(node.path("overallVisualSummary").asText(""));
        if (node.path("scenes").isArray()) {
            for (JsonNode s : node.path("scenes")) {
                SceneItem item = new SceneItem();
                double rs = s.path("relativeStartSec").asDouble(0);
                double re = s.path("relativeEndSec").asDouble(rs + 1);
                item.setStartSec(chunkStartSec + rs);
                item.setEndSec(chunkStartSec + re);
                item.setStartTimestamp(NvidiaOmniVideoAdapter.formatTs(item.getStartSec()));
                item.setEndTimestamp(NvidiaOmniVideoAdapter.formatTs(item.getEndSec()));
                item.setDescription(s.path("description").asText(""));
                ch.getScenes().add(item);
            }
        }
        if (node.path("onScreenTexts").isArray()) {
            for (JsonNode o : node.path("onScreenTexts")) {
                OnScreenTextItem item = new OnScreenTextItem();
                item.setStartSec(chunkStartSec + o.path("relativeSec").asDouble(0));
                item.setTimestamp(NvidiaOmniVideoAdapter.formatTs(item.getStartSec()));
                item.setText(o.path("text").asText(""));
                ch.getOnScreenTexts().add(item);
            }
        }
        if (node.path("visualKeyPoints").isArray()) {
            for (JsonNode k : node.path("visualKeyPoints")) {
                VisualKeyPointItem item = new VisualKeyPointItem();
                item.setStartSec(chunkStartSec + k.path("relativeSec").asDouble(0));
                item.setTimestamp(NvidiaOmniVideoAdapter.formatTs(item.getStartSec()));
                item.setPoint(k.path("point").asText(""));
                item.setSource(k.path("source").asText("visual"));
                ch.getVisualKeyPoints().add(item);
            }
        }
        return ch;
    }

    private String requireApiKey(String providerKey) {
        ProviderConfig pc = aiProperties.getProvider(providerKey);
        if (pc == null || pc.getApiKey() == null || pc.getApiKey().isBlank()) {
            throw new BusinessException("[FRAME] 供应商不可用: " + providerKey);
        }
        return pc.getApiKey();
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
