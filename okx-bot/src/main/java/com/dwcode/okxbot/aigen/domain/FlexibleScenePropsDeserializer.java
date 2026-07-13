package com.dwcode.okxbot.aigen.domain;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 宽松解析场景 props。LLM 常见错误形态：
 * <ul>
 *   <li>正常：{"title":"…","items":["a"]}</li>
 *   <li>数组键值：["eyebrow=标签","title=标题"] 或 ["title: 标题"]</li>
 *   <li>对象数组：[{"key":"title","value":"…"}]</li>
 *   <li>纯字符串数组：当 items 用（仅无 key= 时写入 items）</li>
 * </ul>
 */
public class FlexibleScenePropsDeserializer extends JsonDeserializer<SceneProps> {

    private static final Set<String> LIST_KEYS = Set.of(
            "items", "leftitems", "rightitems", "left_items", "right_items"
    );

    private static final Set<String> SCALAR_KEYS = Set.of(
            "title", "subtitle", "heading", "cta", "eyebrow",
            "leftlabel", "rightlabel", "left_label", "right_label",
            "value", "unit", "label", "hint"
    );

    @Override
    public SceneProps deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        ObjectMapper mapper = (ObjectMapper) p.getCodec();
        JsonToken t = p.currentToken();
        if (t == null) {
            t = p.nextToken();
        }
        if (t == null || t == JsonToken.VALUE_NULL) {
            return new SceneProps();
        }

        JsonNode node = p.readValueAsTree();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return new SceneProps();
        }

        ObjectNode objectForm;
        if (node.isObject()) {
            objectForm = (ObjectNode) node;
        } else if (node.isArray()) {
            objectForm = arrayToObject(mapper, (ArrayNode) node);
        } else if (node.isTextual()) {
            objectForm = mapper.createObjectNode();
            String s = node.asText().trim();
            if (!s.isEmpty()) {
                objectForm.put("title", s);
            }
        } else {
            objectForm = mapper.createObjectNode();
        }

        normalizeListFields(mapper, objectForm);
        try {
            SceneProps props = mapper.treeToValue(objectForm, SceneProps.class);
            return props != null ? props : new SceneProps();
        } catch (Exception e) {
            // 字段类型仍异常时尽力手工填标量
            return manualFill(objectForm);
        }
    }

    private static ObjectNode arrayToObject(ObjectMapper mapper, ArrayNode arr) {
        ObjectNode obj = mapper.createObjectNode();
        List<String> looseItems = new ArrayList<>();

        for (JsonNode el : arr) {
            if (el == null || el.isNull()) {
                continue;
            }
            if (el.isTextual() || el.isNumber() || el.isBoolean()) {
                String s = el.asText("").trim();
                if (s.isEmpty()) {
                    continue;
                }
                if (!applyKeyValueLine(obj, s)) {
                    looseItems.add(s);
                }
                continue;
            }
            if (el.isObject()) {
                // {"key":"title","value":"x"} 或直接 {"title":"x"}
                if (el.has("key") || el.has("name") || el.has("field")) {
                    String k = firstText(el, "key", "name", "field");
                    String v = firstText(el, "value", "text", "content", "val");
                    if (k != null && v != null) {
                        putFlexible(obj, k, v);
                        continue;
                    }
                }
                Iterator<Map.Entry<String, JsonNode>> it = el.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    mergeField(obj, e.getKey(), e.getValue());
                }
            }
        }

        if (!looseItems.isEmpty() && !obj.has("items")) {
            ArrayNode items = obj.putArray("items");
            for (String s : looseItems) {
                items.add(s);
            }
        }
        return obj;
    }

    /** 解析 "key=value" / "key: value" / "key：value" */
    private static boolean applyKeyValueLine(ObjectNode obj, String line) {
        String s = line.trim();
        int idx = indexOfKvSep(s);
        if (idx <= 0) {
            return false;
        }
        String key = s.substring(0, idx).trim();
        String val = s.substring(idx + 1).trim();
        // 去掉可能的引号
        if (val.length() >= 2) {
            char a = val.charAt(0);
            char b = val.charAt(val.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                val = val.substring(1, val.length() - 1);
            }
        }
        if (key.isEmpty()) {
            return false;
        }
        String nk = normalizeKey(key);
        if (!SCALAR_KEYS.contains(nk) && !LIST_KEYS.contains(nk)) {
            // 未知 key 仍写入，便于扩展
            if (val.contains("|") || val.contains("、") || val.contains(",")) {
                putList(obj, key, val);
            } else {
                obj.put(key, val);
            }
            return true;
        }
        if (LIST_KEYS.contains(nk)) {
            putList(obj, canonicalListKey(nk), val);
        } else {
            putFlexible(obj, key, val);
        }
        return true;
    }

    private static int indexOfKvSep(String s) {
        int eq = s.indexOf('=');
        int colon = s.indexOf(':');
        int fullColon = s.indexOf('：');
        int best = -1;
        for (int i : new int[]{eq, colon, fullColon}) {
            if (i > 0 && (best < 0 || i < best)) {
                best = i;
            }
        }
        // 避免 "https://" 误判：key 过长或含空格多则不算
        if (best > 0 && best <= 24) {
            String key = s.substring(0, best).trim();
            if (key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                return best;
            }
        }
        return -1;
    }

    private static void putFlexible(ObjectNode obj, String key, String val) {
        String nk = normalizeKey(key);
        String field = switch (nk) {
            case "leftlabel", "left_label" -> "leftLabel";
            case "rightlabel", "right_label" -> "rightLabel";
            case "leftitems", "left_items" -> "leftItems";
            case "rightitems", "right_items" -> "rightItems";
            default -> key.contains("_") ? camel(key) : key;
        };
        if (LIST_KEYS.contains(nk)) {
            putList(obj, field, val);
        } else {
            obj.put(field, val);
        }
    }

    private static void putList(ObjectNode obj, String field, String val) {
        ArrayNode arr = obj.withArray(field);
        for (String part : val.split("[,|、；;]+")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                arr.add(t);
            }
        }
    }

    private static String canonicalListKey(String nk) {
        return switch (nk) {
            case "leftitems", "left_items" -> "leftItems";
            case "rightitems", "right_items" -> "rightItems";
            default -> "items";
        };
    }

    private static void mergeField(ObjectNode obj, String key, JsonNode value) {
        if (value == null || value.isNull()) {
            return;
        }
        String nk = normalizeKey(key);
        if (LIST_KEYS.contains(nk) || value.isArray()) {
            String field = LIST_KEYS.contains(nk) ? canonicalListKey(nk) : key;
            ArrayNode arr = obj.withArray(field);
            if (value.isArray()) {
                for (JsonNode c : value) {
                    if (c != null && !c.isNull()) {
                        arr.add(c.isValueNode() ? c.asText() : c.toString());
                    }
                }
            } else {
                arr.add(value.asText());
            }
            return;
        }
        putFlexible(obj, key, value.asText());
    }

    private static void normalizeListFields(ObjectMapper mapper, ObjectNode obj) {
        // leftItems 若是字符串，拆开
        for (String k : List.of("items", "leftItems", "rightItems")) {
            if (!obj.has(k)) {
                continue;
            }
            JsonNode n = obj.get(k);
            if (n != null && n.isTextual()) {
                ArrayNode arr = mapper.createArrayNode();
                for (String part : n.asText().split("[,|、；;]+")) {
                    String t = part.trim();
                    if (!t.isEmpty()) {
                        arr.add(t);
                    }
                }
                obj.set(k, arr);
            }
        }
    }

    private static SceneProps manualFill(ObjectNode obj) {
        SceneProps p = new SceneProps();
        p.setTitle(text(obj, "title"));
        p.setSubtitle(text(obj, "subtitle"));
        p.setHeading(text(obj, "heading"));
        p.setCta(text(obj, "cta"));
        p.setEyebrow(text(obj, "eyebrow"));
        p.setLeftLabel(text(obj, "leftLabel"));
        p.setRightLabel(text(obj, "rightLabel"));
        p.setValue(text(obj, "value"));
        p.setUnit(text(obj, "unit"));
        p.setLabel(text(obj, "label"));
        p.setHint(text(obj, "hint"));
        p.setItems(stringList(obj.get("items")));
        p.setLeftItems(stringList(obj.get("leftItems")));
        p.setRightItems(stringList(obj.get("rightItems")));
        return p;
    }

    private static List<String> stringList(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n == null || n.isNull()) {
            return out;
        }
        if (n.isArray()) {
            for (JsonNode c : n) {
                if (c != null && !c.isNull() && !c.asText("").isBlank()) {
                    out.add(c.asText().trim());
                }
            }
        } else if (n.isTextual() && !n.asText().isBlank()) {
            out.add(n.asText().trim());
        }
        return out;
    }

    private static String text(ObjectNode obj, String key) {
        JsonNode n = obj.get(key);
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asText(null);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String firstText(JsonNode obj, String... keys) {
        for (String k : keys) {
            if (obj.has(k) && !obj.get(k).isNull()) {
                String s = obj.get(k).asText("");
                if (!s.isBlank()) {
                    return s.trim();
                }
            }
        }
        return null;
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT).replace("-", "");
    }

    private static String camel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                up = true;
            } else if (up) {
                sb.append(Character.toUpperCase(c));
                up = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
