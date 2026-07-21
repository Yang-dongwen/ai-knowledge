package com.dwcode.okxbot.common.ai;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 LangChain4j / 原始文本抽取助手内容，兼容 reasoning 与 markdown 围栏 JSON。
 */
public final class LlmContentHelper {

    private static final Pattern JSON_BLOCK = Pattern.compile("(?s)```(?:json)?\\s*(\\{.*?})\\s*```");
    /** 推理模型常见标签：&lt;think&gt;…&lt;/think&gt; 等，会破坏 JSON */
    private static final Pattern THINK_BLOCK = Pattern.compile(
            "(?is)<\\s*(think|thinking|reasoning|redacted_reasoning)\\b[^>]*>.*?</\\s*\\1\\s*>");
    private static final Pattern THINK_TAG = Pattern.compile(
            "(?is)</?\\s*(think|thinking|reasoning|redacted_reasoning)\\b[^>]*>");
    private static final Pattern TRAILING_COMMA = Pattern.compile(",\\s*([}\\]])");

    /** 宽松 JSON 解析（允许尾逗号等） */
    private static final ObjectMapper LENIENT_JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonParser.Feature.ALLOW_COMMENTS)
            .build();

    private LlmContentHelper() {
    }

    /**
     * 从 ChatResponse 取文本：优先 text，空则 thinking（returnThinking 时映射 reasoning）。
     */
    public static String extractText(ChatResponse response) {
        if (response == null || response.aiMessage() == null) {
            throw new BusinessException("LLM 返回为空");
        }
        return extractText(response.aiMessage());
    }

    public static String extractText(AiMessage aiMessage) {
        if (aiMessage == null) {
            throw new BusinessException("LLM 返回为空");
        }
        String text = aiMessage.text();
        if (text != null && !text.isBlank()) {
            return text;
        }
        // DeepSeek / 部分推理模型：正文在 thinking
        String thinking = aiMessage.thinking();
        if (thinking != null && !thinking.isBlank()) {
            return thinking;
        }
        throw new BusinessException("LLM 返回空内容");
    }

    /**
     * 从可能带 markdown 代码围栏的文本中提取 JSON 对象字符串。
     */
    public static String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("LLM 返回为空");
        }
        String t = stripNoise(raw);
        Matcher m = JSON_BLOCK.matcher(t);
        if (m.find()) {
            return sanitizeJsonText(m.group(1));
        }
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                t = t.substring(firstNl + 1, lastFence).trim();
            }
        }
        String balanced = extractBalancedJsonObject(t);
        if (balanced != null) {
            return sanitizeJsonText(balanced);
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return sanitizeJsonText(t.substring(start, end + 1));
        }
        throw new BusinessException("无法从 LLM 响应解析 JSON 对象");
    }

    /**
     * 宽松提取：找不到对象时返回原文（摘要解析等可再兜底）。
     */
    public static String extractJsonObjectOrRaw(String raw) {
        try {
            return extractJsonObject(raw);
        } catch (BusinessException e) {
            return raw == null ? "" : stripNoise(raw.trim());
        }
    }

    /**
     * 解析 LLM 输出的 JSON：清洗 → 严格解析 → 宽松解析 → 截断修复。
     *
     * @throws BusinessException 仍无法解析时
     */
    public static JsonNode parseJsonLenient(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("LLM 返回为空");
        }
        String cleaned = extractJsonObjectOrRaw(raw);
        Exception last = null;
        for (String candidate : candidatesForParse(cleaned)) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                return LENIENT_JSON.readTree(candidate);
            } catch (Exception e) {
                last = e;
            }
            try {
                return new ObjectMapper().readTree(candidate);
            } catch (Exception e) {
                last = e;
            }
        }
        // 最后兜底：只抽 overallVisualSummary 等关键字段拼最小对象
        JsonNode minimal = buildMinimalVisualJson(cleaned != null ? cleaned : raw);
        if (minimal != null) {
            return minimal;
        }
        throw new BusinessException("解析 LLM JSON 失败: "
                + (last != null ? last.getMessage() : "unknown")
                + " | snippet=" + truncate(cleaned, 200));
    }

    /**
     * 将 LLM 文本解析为业务 DTO：提取 JSON → 引号修复 → 宽松/严格解析 → tree→bean。
     * 专治画面 prompt 内嵌未转义双引号（如「显示"AI发展"」）导致的 Jackson 失败。
     */
    public static <T> T parseJsonAs(ObjectMapper objectMapper, String raw, Class<T> type) {
        if (objectMapper == null || type == null) {
            throw new BusinessException("JSON 解析参数无效");
        }
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("LLM 返回为空");
        }
        String cleaned;
        try {
            cleaned = extractJsonObject(raw);
        } catch (BusinessException e) {
            cleaned = extractJsonObjectOrRaw(raw);
        }
        Exception last = null;
        for (String candidate : candidatesForParse(cleaned)) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                return objectMapper.readValue(candidate, type);
            } catch (Exception e) {
                last = e;
            }
            try {
                JsonNode tree = LENIENT_JSON.readTree(candidate);
                return objectMapper.convertValue(tree, type);
            } catch (Exception e) {
                last = e;
            }
        }
        throw new BusinessException("解析 LLM JSON 失败: "
                + (last != null ? last.getMessage() : "unknown")
                + " | snippet=" + truncate(cleaned, 240));
    }

    /** 生成若干可尝试的 JSON 候选（原样清洗 → 转义内嵌引号 → 截断补全）。 */
    static String[] candidatesForParse(String cleaned) {
        if (cleaned == null || cleaned.isBlank()) {
            return new String[0];
        }
        String escaped = escapeUnescapedQuotesInStrings(cleaned);
        String salvaged = salvageJsonByTruncation(cleaned);
        String salvagedEscaped = salvaged != null ? escapeUnescapedQuotesInStrings(salvaged) : null;
        // 去重但保持顺序
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        set.add(cleaned);
        if (escaped != null && !escaped.equals(cleaned)) {
            set.add(escaped);
        }
        if (salvaged != null) {
            set.add(salvaged);
        }
        if (salvagedEscaped != null) {
            set.add(salvagedEscaped);
        }
        return set.toArray(new String[0]);
    }

    /** 去掉 think 标签、BOM、零宽字符等 */
    public static String stripNoise(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw;
        // UTF-8 BOM
        if (!t.isEmpty() && t.charAt(0) == '\uFEFF') {
            t = t.substring(1);
        }
        t = THINK_BLOCK.matcher(t).replaceAll("");
        t = THINK_TAG.matcher(t).replaceAll("");
        // 偶发 XML/HTML 噪声行（整行是标签）
        t = t.replaceAll("(?m)^\\s*<[^>\\n]{1,80}>\\s*$", "");
        return t.trim();
    }

    public static String sanitizeJsonText(String json) {
        if (json == null) {
            return "";
        }
        String t = stripNoise(json);
        // 注意：禁止把弯引号 “” 改成直引号 "。
        // 模型常在 prompt 里写「显示“AI发展”」；改成直引号会直接把 JSON 字符串打断
        // （Jackson: Unexpected character 'A' was expecting comma...）。
        // 弯引号本身是合法 Unicode，可安全留在字符串值内。
        // 尾逗号
        t = TRAILING_COMMA.matcher(t).replaceAll("$1");
        return t.trim();
    }

    /**
     * 修复字符串值内未转义的直双引号。
     * 规则：已进入 JSON 字符串后，若遇到 {@code "}，看后续非空白字符是否为结构符
     * {@code : , } ] }；不是则视为内容引号，转义为 {@code \"}。
     * <p>
     * 例：{@code "prompt":"显示"AI"趋势"} → {@code "prompt":"显示\"AI\"趋势"}
     */
    public static String escapeUnescapedQuotesInStrings(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        StringBuilder out = new StringBuilder(json.length() + 16);
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (!inString) {
                out.append(c);
                if (c == '"') {
                    inString = true;
                    escape = false;
                }
                continue;
            }
            // in string
            if (escape) {
                out.append(c);
                escape = false;
                continue;
            }
            if (c == '\\') {
                out.append(c);
                escape = true;
                continue;
            }
            if (c == '"') {
                if (looksLikeStringTerminator(json, i + 1)) {
                    out.append(c);
                    inString = false;
                } else {
                    out.append('\\').append('"');
                }
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * 判断位置 {@code from} 起的下一个“有意义”字符是否像字符串结束之后的结构符。
     */
    static boolean looksLikeStringTerminator(String json, int from) {
        int i = from;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
                continue;
            }
            // key 结束 → : ；value 结束 → , } ]
            return c == ':' || c == ',' || c == '}' || c == ']';
        }
        // 文本已结束：当作闭合引号（残缺 JSON 交给后续 salvage）
        return true;
    }

    /**
     * 从文本中按括号深度提取第一个完整 JSON 对象（正确处理字符串内括号）。
     */
    public static String extractBalancedJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /**
     * 当 JSON 因中途插入非法字符失败时，截到最后一个完整的 } 并修尾逗号再试。
     */
    static String salvageJsonByTruncation(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        // 从后往前找可能的合法截断点
        for (int i = json.length() - 1; i >= 0; i--) {
            if (json.charAt(i) != '}') {
                continue;
            }
            String candidate = sanitizeJsonText(json.substring(0, i + 1));
            // 补齐未闭合的 [ {
            int openBrace = 0, openBracket = 0;
            boolean inString = false, escape = false;
            for (int j = 0; j < candidate.length(); j++) {
                char c = candidate.charAt(j);
                if (inString) {
                    if (escape) {
                        escape = false;
                    } else if (c == '\\') {
                        escape = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    openBrace++;
                } else if (c == '}') {
                    openBrace--;
                } else if (c == '[') {
                    openBracket++;
                } else if (c == ']') {
                    openBracket--;
                }
            }
            if (inString) {
                candidate = candidate + "\"";
            }
            StringBuilder sb = new StringBuilder(candidate);
            while (openBracket > 0) {
                sb.append(']');
                openBracket--;
            }
            while (openBrace > 0) {
                sb.append('}');
                openBrace--;
            }
            String fixed = sanitizeJsonText(sb.toString());
            try {
                LENIENT_JSON.readTree(fixed);
                return fixed;
            } catch (Exception ignored) {
                // try earlier }
            }
        }
        return null;
    }

    /**
     * 从残缺文本里正则抽 overallVisualSummary，保证不至于整段理解失败。
     */
    static JsonNode buildMinimalVisualJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            String summary = null;
            Matcher m = Pattern.compile(
                    "\"overallVisualSummary\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
                    Pattern.DOTALL).matcher(text);
            if (m.find()) {
                summary = m.group(1)
                        .replace("\\\"", "\"")
                        .replace("\\n", "\n")
                        .replace("\\\\", "\\");
            }
            if (summary == null || summary.isBlank()) {
                // 无引号兜底：取前 200 字可见中文
                Matcher m2 = Pattern.compile("overallVisualSummary\"?\\s*[:=]\\s*\"?([^\"\\n\\r{]{8,400})")
                        .matcher(text);
                if (m2.find()) {
                    summary = m2.group(1).trim();
                }
            }
            if (summary == null || summary.isBlank()) {
                return null;
            }
            ObjectMapper om = new ObjectMapper();
            var node = om.createObjectNode();
            node.put("overallVisualSummary", summary);
            node.putArray("scenes");
            node.putArray("onScreenTexts");
            node.putArray("visualKeyPoints");
            return node;
        } catch (Exception e) {
            return null;
        }
    }

    public static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
