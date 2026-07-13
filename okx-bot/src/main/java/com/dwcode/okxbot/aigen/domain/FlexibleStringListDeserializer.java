package com.dwcode.okxbot.aigen.domain;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 宽松解析 LLM 返回的字符串列表：
 * <ul>
 *   <li>["a","b"]</li>
 *   <li>[{"text":"a"},{"value":"b"}]</li>
 *   <li>"单条字符串"</li>
 *   <li>null → 空列表</li>
 * </ul>
 */
public class FlexibleStringListDeserializer extends JsonDeserializer<List<String>> {

    @Override
    public List<String> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        List<String> out = new ArrayList<>();
        JsonToken t = p.currentToken();
        if (t == null) {
            t = p.nextToken();
        }
        if (t == JsonToken.VALUE_NULL) {
            return out;
        }
        if (t == JsonToken.VALUE_STRING || t == JsonToken.VALUE_NUMBER_INT
                || t == JsonToken.VALUE_NUMBER_FLOAT || t == JsonToken.VALUE_TRUE
                || t == JsonToken.VALUE_FALSE) {
            String s = nodeToString(p.readValueAsTree());
            if (s != null && !s.isBlank()) {
                out.add(s.trim());
            }
            return out;
        }
        if (t == JsonToken.START_OBJECT) {
            String s = nodeToString(p.readValueAsTree());
            if (s != null && !s.isBlank()) {
                out.add(s.trim());
            }
            return out;
        }
        if (t != JsonToken.START_ARRAY) {
            // 未知形态：跳过
            p.skipChildren();
            return out;
        }
        while (p.nextToken() != JsonToken.END_ARRAY) {
            JsonToken cur = p.currentToken();
            if (cur == JsonToken.VALUE_NULL) {
                continue;
            }
            if (cur == JsonToken.START_ARRAY) {
                // 嵌套数组：拍平一层
                while (p.nextToken() != JsonToken.END_ARRAY) {
                    String s = nodeToString(p.readValueAsTree());
                    if (s != null && !s.isBlank()) {
                        out.add(s.trim());
                    }
                }
                continue;
            }
            String s = nodeToString(p.readValueAsTree());
            if (s != null && !s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    private static String nodeToString(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isNumber() || n.isBoolean()) {
            return n.asText();
        }
        if (n.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode c : n) {
                String s = nodeToString(c);
                if (s != null && !s.isBlank()) {
                    parts.add(s.trim());
                }
            }
            return parts.isEmpty() ? null : String.join("、", parts);
        }
        if (n.isObject()) {
            // 常见 LLM 形态：{text}/{content}/{value}/{title}/{label}/{item}/{point}
            String[] keys = {
                    "text", "content", "value", "title", "label", "item",
                    "point", "name", "desc", "description", "bullet", "line"
            };
            for (String k : keys) {
                if (n.has(k) && !n.get(k).isNull()) {
                    String s = nodeToString(n.get(k));
                    if (s != null && !s.isBlank()) {
                        return s;
                    }
                }
            }
            // 取第一个标量字段
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                if (v != null && (v.isValueNode() || v.isTextual())) {
                    String s = v.asText();
                    if (s != null && !s.isBlank()) {
                        return s.trim();
                    }
                }
            }
            // 最后兜底：整对象 toString 不理想，返回 null
            return null;
        }
        return n.asText(null);
    }
}
