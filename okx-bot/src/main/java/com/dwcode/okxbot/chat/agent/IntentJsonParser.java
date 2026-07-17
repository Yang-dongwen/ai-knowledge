package com.dwcode.okxbot.chat.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 LLM 输出中解析工具意图 JSON。
 * <p>
 * 支持：
 * <pre>
 * {"tool":"list_my_tasks","args":{"type":"imggen","limit":10}}
 * {"tool":null,"reply":"纯文本回复"}
 * </pre>
 * 以及夹在 markdown 代码块中的 JSON。
 */
@Slf4j
@Component
public class IntentJsonParser {

    private static final Pattern JSON_OBJECT = Pattern.compile("(?s)\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}");
    private static final Pattern FENCE = Pattern.compile("(?s)```(?:json)?\\s*(\\{.*?})\\s*```");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value
    public static class AgentIntent {
        /** null 表示纯聊天 */
        String tool;
        Map<String, Object> args;
        /** 当 tool 为 null 时可带直接回复 */
        String reply;
        /** 原始解析是否成功 */
        boolean parsed;
    }

    public AgentIntent parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new AgentIntent(null, Map.of(), null, false);
        }
        String text = raw.trim();
        // 1) 整段就是 JSON
        AgentIntent direct = tryParseObject(text);
        if (direct != null && direct.isParsed()) {
            return direct;
        }
        // 2) markdown fence
        Matcher fence = FENCE.matcher(text);
        if (fence.find()) {
            AgentIntent inFence = tryParseObject(fence.group(1));
            if (inFence != null && inFence.isParsed()) {
                return inFence;
            }
        }
        // 3) 文本中第一个 { ... }
        Matcher m = JSON_OBJECT.matcher(text);
        while (m.find()) {
            AgentIntent mid = tryParseObject(m.group());
            if (mid != null && mid.isParsed() && (mid.getTool() != null || mid.getReply() != null)) {
                return mid;
            }
        }
        // 解析失败：整段当 reply
        return new AgentIntent(null, Map.of(), text, false);
    }

    private AgentIntent tryParseObject(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                return null;
            }
            // 必须含 tool 字段（可为 null）才认为是意图协议
            if (!node.has("tool") && !node.has("reply")) {
                return null;
            }
            String tool = null;
            if (node.has("tool") && !node.get("tool").isNull()) {
                tool = node.get("tool").asText(null);
                if (tool != null && tool.isBlank()) {
                    tool = null;
                }
            }
            String reply = node.path("reply").asText(null);
            Map<String, Object> args = Collections.emptyMap();
            if (node.has("args") && node.get("args").isObject()) {
                args = jsonObjectToMap(node.get("args"));
            }
            return new AgentIntent(tool, args, reply, true);
        } catch (Exception e) {
            log.debug("意图 JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> jsonObjectToMap(JsonNode obj) {
        Map<String, Object> map = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            if (v == null || v.isNull()) {
                map.put(e.getKey(), null);
            } else if (v.isNumber()) {
                if (v.isIntegralNumber()) {
                    map.put(e.getKey(), v.longValue());
                } else {
                    map.put(e.getKey(), v.doubleValue());
                }
            } else if (v.isBoolean()) {
                map.put(e.getKey(), v.booleanValue());
            } else if (v.isTextual()) {
                map.put(e.getKey(), v.asText());
            } else {
                map.put(e.getKey(), v.toString());
            }
        }
        return map;
    }
}
