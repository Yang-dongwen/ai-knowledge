package com.dwcode.okxbot.article.adapter.llm;

import com.dwcode.okxbot.article.config.ArticleProperties;
import com.dwcode.okxbot.article.enums.ArticleErrorCode;
import com.dwcode.okxbot.article.port.ArticleCoreCommand;
import com.dwcode.okxbot.article.port.ArticleCoreLlmPort;
import com.dwcode.okxbot.article.port.ArticleCoreResult;
import com.dwcode.okxbot.article.port.MainTextDocument;
import com.dwcode.okxbot.article.service.ArticlePromptLoader;
import com.dwcode.okxbot.common.ai.LlmCallOptions;
import com.dwcode.okxbot.common.ai.LlmChatClient;
import com.dwcode.okxbot.common.ai.LlmContentHelper;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文章 CORE 提取：经 {@link LlmChatClient}（默认 LangChain4j ChatModel）。
 * <p>支持长文分窗 map-reduce、json_object、一次 fill 补全。
 */
@Slf4j
@RequiredArgsConstructor
public class LangchainArticleCoreAdapter implements ArticleCoreLlmPort {

    private static final int HARD_CAP = 60_000;

    private final LlmChatClient llmChatClient;
    private final ArticleProperties properties;
    private final ArticlePromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    @Override
    public ArticleCoreResult extractCore(MainTextDocument doc, ArticleCoreCommand cmd) {
        if (doc == null || doc.getMainText() == null || doc.getMainText().isBlank()) {
            throw new BusinessException(400, ArticleErrorCode.EMPTY_MAIN_TEXT + ": 正文为空");
        }
        String main = doc.getMainText();
        boolean truncatedInput = doc.isTruncated();
        int mapCalls = 0;

        int single = Math.max(1000, properties.getLlm().getDigestSingleWindowChars());
        int window = Math.max(1500, properties.getLlm().getDigestWindowChars());

        String material;
        if (main.length() <= single) {
            material = main;
        } else if (main.length() <= HARD_CAP) {
            List<String> parts = splitByChars(main, window);
            StringBuilder reduced = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                String partial = mapWindow(parts.get(i), cmd, i + 1, parts.size());
                mapCalls++;
                reduced.append("【段").append(i + 1).append("】\n").append(partial).append("\n\n");
            }
            material = reduced.toString();
        } else {
            int head = (int) (main.length() * 0.4);
            int tail = (int) (main.length() * 0.2);
            material = main.substring(0, head) + "\n\n…(中间省略)…\n\n"
                    + main.substring(main.length() - tail);
            truncatedInput = true;
        }

        String system = promptLoader.load("core_system.txt");
        if (system.isBlank()) {
            system = "你是新闻分析师，只输出合法 JSON。";
        }
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("extractMindMap", String.valueOf(cmd.isExtractMindMap()));
        vars.put("language", nullTo(cmd.getLanguage(), "zh"));
        vars.put("platform", nullTo(cmd.getPlatform(), "generic"));
        vars.put("sourceUrl", nullTo(cmd.getSourceUrl(), ""));
        vars.put("titleHint", nullTo(firstNonBlank(cmd.getTitleHint(), doc.getTitle()), ""));
        vars.put("mainText", material);

        String user = promptLoader.render("core_user_template.txt", vars);
        if (user.isBlank()) {
            user = "请提取核心 JSON。正文：\n" + material;
        }

        LlmCallOptions opts = buildJsonOptions(properties.getLlm().getCoreTemperature(), 4096);
        String raw;
        try {
            raw = chatJson(system, user, cmd.getLlmProvider(), cmd.getLlmModel(), opts);
        } catch (Exception e) {
            throw new BusinessException(500, ArticleErrorCode.LLM_CORE_FAILED + ": " + e.getMessage());
        }

        JsonNode root = tryParse(raw);
        if (!isCoreValid(root, cmd.isExtractMindMap())) {
            log.warn("CORE 字段不完整，触发一次补全");
            root = fillRepair(root, raw, material, cmd, opts);
        }
        if (!isCoreValid(root, cmd.isExtractMindMap())) {
            throw new BusinessException(500, ArticleErrorCode.LLM_CORE_FAILED + ": 必填字段缺失或 JSON 非法");
        }

        // 写入 truncatedInput
        if (root instanceof ObjectNode on) {
            on.put("truncatedInput", truncatedInput || on.path("truncatedInput").asBoolean(false));
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            json = root.toString();
        }

        return ArticleCoreResult.builder()
                .rawJson(json)
                .title(text(root, "title"))
                .summary(text(root, "summary"))
                .truncatedInput(truncatedInput)
                .mapLlmCalls(mapCalls)
                .build();
    }

    private String mapWindow(String windowText, ArticleCoreCommand cmd, int idx, int total) {
        Map<String, String> vars = Map.of(
                "language", nullTo(cmd.getLanguage(), "zh"),
                "windowIndex", String.valueOf(idx),
                "windowCount", String.valueOf(total),
                "windowText", windowText
        );
        String system = "你是分段摘要助手，只输出合法 JSON。";
        String user = promptLoader.render("map_window.txt", vars);
        if (user.isBlank()) {
            user = "摘要：\n" + windowText;
        }
        LlmCallOptions opts = buildJsonOptions(0.2, 1024);
        try {
            String raw = chatJson(system, user, cmd.getLlmProvider(), cmd.getLlmModel(), opts);
            JsonNode n = tryParse(raw);
            if (n != null && n.has("partialSummary")) {
                return n.path("partialSummary").asText("");
            }
            return raw.length() > 800 ? raw.substring(0, 800) : raw;
        } catch (Exception e) {
            log.warn("map window 失败 idx={}: {}", idx, e.getMessage());
            return windowText.length() > 400 ? windowText.substring(0, 400) + "…" : windowText;
        }
    }

    private JsonNode fillRepair(JsonNode previous, String previousRaw, String material,
                                ArticleCoreCommand cmd, LlmCallOptions opts) {
        String prevJson;
        try {
            prevJson = previous != null ? objectMapper.writeValueAsString(previous)
                    : (previousRaw != null ? previousRaw : "{}");
        } catch (Exception e) {
            prevJson = previousRaw != null ? previousRaw : "{}";
        }
        String brief = material.length() > 3000 ? material.substring(0, 3000) + "…" : material;
        Map<String, String> vars = Map.of(
                "previousJson", prevJson,
                "mainTextBrief", brief,
                "extractMindMap", String.valueOf(cmd.isExtractMindMap())
        );
        String system = promptLoader.load("core_system.txt");
        String user = promptLoader.render("core_fill_repair.txt", vars);
        try {
            String raw = chatJson(system, user, cmd.getLlmProvider(), cmd.getLlmModel(), opts);
            return tryParse(raw);
        } catch (Exception e) {
            log.warn("CORE fill 失败: {}", e.getMessage());
            return previous;
        }
    }

    /**
     * json_object 优先；供应商不支持时降级去 fence。
     */
    private String chatJson(String system, String user, String provider, String model, LlmCallOptions opts) {
        try {
            String raw = llmChatClient.chat(system, user, provider, model, opts);
            return LlmContentHelper.extractJsonObject(raw);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (opts.getResponseFormat() != null
                    && (msg.contains("response_format") || msg.contains("json_object")
                    || msg.contains("unsupported"))) {
                log.warn("response_format 不支持，降级纯文本: {}", e.getMessage());
                LlmCallOptions plain = LlmCallOptions.builder()
                        .temperature(opts.getTemperature())
                        .maxTokens(opts.getMaxTokens())
                        .maxRetries(opts.getMaxRetries())
                        .timeoutSeconds(opts.getTimeoutSeconds())
                        .responseFormat(null)
                        .build();
                String raw = llmChatClient.chat(system, user, provider, model, plain);
                return LlmContentHelper.extractJsonObject(raw);
            }
            throw e instanceof RuntimeException re ? re : new BusinessException(500, e.getMessage());
        }
    }

    private LlmCallOptions buildJsonOptions(double temperature, int maxTokens) {
        boolean json = properties.getLlm().isJsonObjectEnabled();
        LlmCallOptions.LlmCallOptionsBuilder b = LlmCallOptions.builder()
                .temperature(temperature)
                .maxTokens(maxTokens)
                .maxRetries(2)
                .timeoutSeconds(180);
        if (json) {
            b.responseFormat("json_object");
        }
        return b.build();
    }

    private JsonNode tryParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            try {
                String cleaned = LlmContentHelper.extractJsonObject(raw);
                return objectMapper.readTree(cleaned);
            } catch (Exception e2) {
                log.warn("JSON 解析失败: {}", e2.getMessage());
                return null;
            }
        }
    }

    private static boolean isCoreValid(JsonNode root, boolean mindMap) {
        if (root == null || !root.isObject()) {
            return false;
        }
        String title = text(root, "title");
        String summary = text(root, "summary");
        if (title == null || title.isBlank()) {
            return false;
        }
        if (summary == null || summary.isBlank()) {
            return false;
        }
        JsonNode kps = root.path("keyPoints");
        if (!kps.isArray() || kps.isEmpty()) {
            return false;
        }
        boolean anyPoint = false;
        for (JsonNode kp : kps) {
            if (kp.path("point").asText("").trim().length() > 0) {
                anyPoint = true;
                break;
            }
        }
        if (!anyPoint) {
            return false;
        }
        if (mindMap) {
            String mm = text(root, "mindMapMarkdown");
            if (mm == null || mm.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String text(JsonNode n, String field) {
        if (n == null) {
            return null;
        }
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.asText(null);
    }

    private static List<String> splitByChars(String full, int windowChars) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        while (i < full.length()) {
            int end = Math.min(full.length(), i + windowChars);
            // 尽量在段落边界切
            if (end < full.length()) {
                int nl = full.lastIndexOf('\n', end);
                if (nl > i + windowChars / 2) {
                    end = nl;
                }
            }
            parts.add(full.substring(i, end));
            i = end;
        }
        return parts;
    }

    private static String nullTo(String s, String d) {
        return s == null || s.isBlank() ? d : s;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }
}
