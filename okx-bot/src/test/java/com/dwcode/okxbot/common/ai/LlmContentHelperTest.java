package com.dwcode.okxbot.common.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmContentHelperTest {

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
}
