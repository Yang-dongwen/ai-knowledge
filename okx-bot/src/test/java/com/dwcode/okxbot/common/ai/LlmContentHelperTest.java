package com.dwcode.okxbot.common.ai;

import com.dwcode.okxbot.aigen.domain.shot.ShotlistDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmContentHelperTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void extractJsonObject_fromFence() {
        String raw = "```json\n{\"a\":1}\n```";
        assertEquals("{\"a\":1}", LlmContentHelper.extractJsonObject(raw));
    }

    @Test
    void extractJsonObject_fromPlain() {
        assertEquals("{\"k\":\"v\"}", LlmContentHelper.extractJsonObject("here {\"k\":\"v\"} end"));
    }

    @Test
    void extractJsonObject_blankThrows() {
        assertThrows(Exception.class, () -> LlmContentHelper.extractJsonObject("   "));
    }

    @Test
    void extractJsonObjectOrRaw_fallback() {
        assertTrue(LlmContentHelper.extractJsonObjectOrRaw("not-json").contains("not-json"));
    }

    @Test
    void sanitizeJsonText_keepsCurlyQuotesInsideStrings() {
        // 弯引号必须保留，不能改成直引号，否则会打断 JSON
        String raw = "{\"prompt\":\"屏幕显示“AI发展”趋势\"}";
        String cleaned = LlmContentHelper.sanitizeJsonText(raw);
        assertTrue(cleaned.contains("“AI发展”") || cleaned.contains("\u201cAI\u53d1\u5c55\u201d"));
        assertFalse(cleaned.contains("\"AI发展\"") && !cleaned.contains("“"));
        JsonNode node = LlmContentHelper.parseJsonLenient(raw);
        assertEquals("屏幕显示“AI发展”趋势", node.get("prompt").asText());
    }

    @Test
    void escapeUnescapedQuotes_inPromptLikeUserError() {
        // 复现：Unexpected character ('A') was expecting comma
        String broken = "{\"version\":\"vt-1.0\",\"shots\":[{"
                + "\"id\":\"shot-5\","
                + "\"visual\":{"
                + "\"type\":\"ai_image\","
                + "\"prompt\":\"屏幕上显示\"AI发展\"锐评字样，冷蓝光\","
                + "\"negativePrompt\":\"blurry, low quality\""
                + "}}]}";
        String fixed = LlmContentHelper.escapeUnescapedQuotesInStrings(broken);
        assertTrue(fixed.contains("\\\"AI发展\\\"") || fixed.contains("\\u"));
        JsonNode node = LlmContentHelper.parseJsonLenient(broken);
        assertEquals("屏幕上显示\"AI发展\"锐评字样，冷蓝光",
                node.get("shots").get(0).get("visual").get("prompt").asText());
    }

    @Test
    void parseJsonAs_shotlistWithBareAiQuotes() {
        String broken = """
                {
                  "version":"vt-1.0",
                  "meta":{"title":"AI发展锐评","language":"zh","aspectRatio":"16:9","targetDurationSec":25,"stylePreset":"clean-tech"},
                  "audio":{"mode":"none"},
                  "shots":[
                    {
                      "id":"shot-1",
                      "durationSec":3.8,
                      "visual":{
                        "type":"ai_image",
                        "prompt":"实验室屏幕标注"AI"核心指标，全息神经网络",
                        "negativePrompt":"unrelated cityscape, random people, watermark, blurry, low quality, deformed, wrong subject"
                      },
                      "motion":{"type":"punch_in","params":{"intensity":0.7}},
                      "transition":{"type":"crossfade","durationFrames":10},
                      "overlay":{"layout":"none","title":"","subtitle":"","bullets":[]}
                    }
                  ]
                }
                """;
        ShotlistDto dto = LlmContentHelper.parseJsonAs(mapper, broken, ShotlistDto.class);
        assertNotNull(dto);
        assertEquals(1, dto.getShots().size());
        assertTrue(dto.getShots().get(0).getVisual().getPrompt().contains("AI"));
        assertTrue(dto.getShots().get(0).getVisual().getPrompt().contains("实验室"));
    }

    @Test
    void escapeUnescapedQuotes_preservesValidJson() {
        String valid = "{\"a\":\"say \\\"hi\\\"\",\"b\":2,\"c\":{\"d\":\"x\"}}";
        String fixed = LlmContentHelper.escapeUnescapedQuotesInStrings(valid);
        assertEquals(valid, fixed);
        assertEquals("say \"hi\"", LlmContentHelper.parseJsonLenient(valid).get("a").asText());
    }

    @Test
    void looksLikeStringTerminator_basics() {
        assertTrue(LlmContentHelper.looksLikeStringTerminator(",\"x\":1", 0));
        assertTrue(LlmContentHelper.looksLikeStringTerminator("  : 1", 0));
        assertTrue(LlmContentHelper.looksLikeStringTerminator("}", 0));
        assertFalse(LlmContentHelper.looksLikeStringTerminator("AI发展\"", 0));
    }
}
