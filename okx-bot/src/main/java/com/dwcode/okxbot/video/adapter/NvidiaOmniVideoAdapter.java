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
 * NVIDIA Nemotron Omni：chat.completions + video_url base64。
 */
@Slf4j
@Component
public class NvidiaOmniVideoAdapter {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final VideoProperties videoProperties;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    public NvidiaOmniVideoAdapter(VideoProperties videoProperties,
                                  AiProperties aiProperties,
                                  ObjectMapper objectMapper) {
        this.videoProperties = videoProperties;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        VideoProperties.Understanding u = videoProperties.getUnderstanding();
        int read = Math.max(60, u.getTimeoutSeconds());
        int write = Math.max(30, u.getWriteTimeoutSeconds());
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(write, TimeUnit.SECONDS)
                .readTimeout(read, TimeUnit.SECONDS)
                .callTimeout(write + read + 30L, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 对单个切片调用 Omni。
     */
    public ChunkUnderstanding understandChunk(Path chunkFile,
                                              double chunkStartSec,
                                              double chunkEndSec,
                                              String language,
                                              String providerKey,
                                              String modelId,
                                              boolean useAudioInVideo,
                                              String asrWindowText) throws Exception {
        String apiKey = requireApiKey(providerKey);
        ProviderConfig pc = aiProperties.getProvider(providerKey);
        String baseUrl = pc.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://integrate.api.nvidia.com/v1";
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String url = baseUrl + "/chat/completions";
        String model = (modelId != null && !modelId.isBlank())
                ? modelId
                : videoProperties.getUnderstanding().getModel();

        byte[] bytes = Files.readAllBytes(chunkFile);
        String b64 = Base64.getEncoder().encodeToString(bytes);
        String dataUrl = "data:video/mp4;base64," + b64;
        // 释放引用帮助 GC
        bytes = null;

        VideoProperties.Understanding u = videoProperties.getUnderstanding();
        String system = """
                你是视频画面分析专家。只输出一个合法 JSON 对象，禁止 markdown 围栏，禁止 <think> 等标签，禁止注释。
                字符串内若出现引号请用中文弯引号或避免；比较大小请写「小于/大于」不要用裸 < >。
                时间戳使用相对本片段起点的秒数。字段：
                {
                  "overallVisualSummary": "本片段画面总述",
                  "scenes": [{"relativeStartSec":0,"relativeEndSec":5,"description":"..."}],
                  "onScreenTexts": [{"relativeSec":1.2,"text":"屏幕字"}],
                  "visualKeyPoints": [{"relativeSec":2,"point":"要点","source":"visual"}]
                }
                scenes/onScreenTexts/visualKeyPoints 各不超过 8 条，overallVisualSummary 不超过 200 字。
                """;
        String lang = (language != null && language.toLowerCase().startsWith("en"))
                ? "Respond in English." : "请使用中文。";
        StringBuilder userText = new StringBuilder();
        userText.append(lang).append('\n');
        userText.append("片段绝对时间窗: ").append(chunkStartSec).append("s ~ ").append(chunkEndSec).append("s\n");
        if (asrWindowText != null && !asrWindowText.isBlank()) {
            userText.append("同期口播参考（可对照，以画面为准判断屏幕字/UI）:\n")
                    .append(asrWindowText.length() > 2000 ? asrWindowText.substring(0, 2000) : asrWindowText)
                    .append('\n');
        }

        List<Map<String, Object>> content = new ArrayList<>();
        Map<String, Object> textPart = new HashMap<>();
        textPart.put("type", "text");
        textPart.put("text", userText.toString());
        content.add(textPart);
        Map<String, Object> videoPart = new HashMap<>();
        videoPart.put("type", "video_url");
        Map<String, Object> videoUrl = new HashMap<>();
        videoUrl.put("url", dataUrl);
        videoPart.put("video_url", videoUrl);
        content.add(videoPart);

        Map<String, Object> systemMsg = Map.of("role", "system", "content", system);
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", content);

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("temperature", u.getTemperature());
        body.put("max_tokens", u.getMapMaxTokens());
        body.put("messages", List.of(systemMsg, userMsg));
        body.put("media_io_kwargs", Map.of("video", Map.of("fps", Math.max(1, u.getTargetFps()))));
        body.put("chat_template_kwargs", Map.of(
                "enable_thinking", u.isEnableThinking(),
                "thinking_token_budget", u.getThinkingTokenBudget()
        ));
        body.put("mm_processor_kwargs", Map.of("use_audio_in_video", useAudioInVideo));

        String json = objectMapper.writeValueAsString(body);
        // 不持有 base64 字符串过久：请求后 body 可丢
        dataUrl = null;
        b64 = null;

        Exception last = null;
        int maxRetries = Math.max(0, u.getMaxRetries());
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                Request request = new Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Accept", "application/json")
                        .post(RequestBody.create(json, JSON))
                        .build();
                try (Response response = httpClient.newCall(request).execute()) {
                    String respBody = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        String snip = respBody.length() > 400 ? respBody.substring(0, 400) : respBody;
                        int code = response.code();
                        if (code == 429 || code == 503) {
                            throw new TransientOmniException("[OMNI] HTTP " + code + ": " + snip);
                        }
                        if (code == 413 || isMediaError(snip)) {
                            throw new MediaOmniException("[OMNI] media error HTTP " + code + ": " + snip);
                        }
                        throw new BusinessException("[OMNI] HTTP " + code + ": " + snip);
                    }
                    // 解析失败不重试 HTTP（避免同一畸形 JSON 连打 4 次 Omni）
                    return parseChunk(respBody, chunkStartSec, chunkEndSec);
                }
            } catch (TransientOmniException e) {
                last = e;
                long sleep = u.getRetryBackoffMs() * (1L << Math.min(attempt, 4));
                log.warn("Omni 瞬时错误 attempt={}/{}: {}，{}ms 后重试", attempt, maxRetries, e.getMessage(), sleep);
                Thread.sleep(Math.min(sleep, 60_000));
            } catch (MediaOmniException e) {
                throw e;
            } catch (BusinessException e) {
                // 业务/解析错误：不重试
                throw e;
            } catch (Exception e) {
                last = e;
                log.warn("Omni 调用异常 attempt={}: {}", attempt, e.getMessage());
                if (attempt >= maxRetries) {
                    break;
                }
                Thread.sleep(u.getRetryBackoffMs());
            }
        }
        throw new BusinessException("[OMNI] 调用失败: " + (last != null ? last.getMessage() : "unknown"));
    }

    private ChunkUnderstanding parseChunk(String respBody, double chunkStartSec, double chunkEndSec)
            throws Exception {
        JsonNode root = objectMapper.readTree(respBody);
        JsonNode message = root.path("choices").path(0).path("message");
        // 优先 content；部分 reasoning 模型会把正文塞进 reasoning_*，但也可能夹杂 think 噪声
        String content = blankToNull(message.path("content").asText(null));
        String reasoning = firstNonBlank(
                blankToNull(message.path("reasoning_content").asText(null)),
                blankToNull(message.path("reasoning").asText(null))
        );
        String text = firstNonBlank(content, reasoning);
        if (text == null || text.isBlank()) {
            throw new BusinessException("[OMNI] empty content");
        }

        JsonNode node;
        try {
            node = LlmContentHelper.parseJsonLenient(text);
        } catch (BusinessException primary) {
            // content 解析失败时再试 reasoning（有时 content 是残缺、reasoning 里才有 JSON）
            if (reasoning != null && content != null && !reasoning.equals(content)) {
                try {
                    node = LlmContentHelper.parseJsonLenient(reasoning);
                    log.warn("Omni content JSON 解析失败，已改用 reasoning 字段: {}", primary.getMessage());
                } catch (BusinessException secondary) {
                    log.error("Omni JSON 解析失败 contentSnippet={} reasoningSnippet={}",
                            LlmContentHelper.truncate(content, 300),
                            LlmContentHelper.truncate(reasoning, 300));
                    throw primary;
                }
            } else {
                log.error("Omni JSON 解析失败 snippet={}", LlmContentHelper.truncate(text, 400));
                throw primary;
            }
        }

        ChunkUnderstanding ch = new ChunkUnderstanding();
        ch.setChunkStartSec(chunkStartSec);
        ch.setChunkEndSec(chunkEndSec);
        ch.setOverallVisualSummary(node.path("overallVisualSummary").asText(""));

        if (node.path("scenes").isArray()) {
            for (JsonNode s : node.path("scenes")) {
                SceneItem item = new SceneItem();
                double rs = s.path("relativeStartSec").asDouble(s.path("relativeSec").asDouble(0));
                double re = s.path("relativeEndSec").asDouble(rs + 1);
                item.setStartSec(chunkStartSec + rs);
                item.setEndSec(chunkStartSec + re);
                item.setStartTimestamp(formatTs(item.getStartSec()));
                item.setEndTimestamp(formatTs(item.getEndSec()));
                item.setDescription(s.path("description").asText(""));
                ch.getScenes().add(item);
            }
        }
        if (node.path("onScreenTexts").isArray()) {
            for (JsonNode o : node.path("onScreenTexts")) {
                OnScreenTextItem item = new OnScreenTextItem();
                double rs = o.path("relativeSec").asDouble(0);
                item.setStartSec(chunkStartSec + rs);
                item.setTimestamp(formatTs(item.getStartSec()));
                item.setText(o.path("text").asText(""));
                ch.getOnScreenTexts().add(item);
            }
        }
        if (node.path("visualKeyPoints").isArray()) {
            for (JsonNode k : node.path("visualKeyPoints")) {
                VisualKeyPointItem item = new VisualKeyPointItem();
                double rs = k.path("relativeSec").asDouble(0);
                item.setStartSec(chunkStartSec + rs);
                item.setTimestamp(formatTs(item.getStartSec()));
                item.setPoint(k.path("point").asText(""));
                item.setSource(k.path("source").asText("visual"));
                ch.getVisualKeyPoints().add(item);
            }
        }
        if (ch.getOverallVisualSummary() == null || ch.getOverallVisualSummary().isBlank()) {
            if (ch.getScenes().isEmpty() && ch.getVisualKeyPoints().isEmpty()) {
                throw new BusinessException("[OMNI] 解析结果为空（无 summary/scenes/keyPoints）");
            }
            ch.setOverallVisualSummary("（画面片段理解完成，摘要字段缺失）");
        }
        return ch;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static boolean isMediaError(String body) {
        if (body == null) {
            return false;
        }
        String l = body.toLowerCase();
        return l.contains("payload too large")
                || l.contains("unsupported")
                || l.contains("video")
                || l.contains("media")
                || l.contains("too large");
    }

    private String requireApiKey(String providerKey) {
        ProviderConfig pc = aiProperties.getProvider(providerKey);
        if (pc == null || pc.getApiKey() == null || pc.getApiKey().isBlank()) {
            throw new BusinessException("[OMNI] 供应商不可用或未配置 api-key: " + providerKey);
        }
        return pc.getApiKey();
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    static String formatTs(double seconds) {
        if (seconds < 0) {
            seconds = 0;
        }
        int total = (int) Math.floor(seconds);
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        if (h > 0) {
            return String.format("%02d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", m, s);
    }

    /** 429/503 等可退避 */
    public static class TransientOmniException extends Exception {
        public TransientOmniException(String m) {
            super(m);
        }
    }

    /** 媒体体积/格式类，可回退 FrameSample */
    public static class MediaOmniException extends Exception {
        public MediaOmniException(String m) {
            super(m);
        }
    }
}
