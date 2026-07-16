package com.dwcode.okxbot.aigen.adapter.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangChain4jScriptPlanAdapterTest {

    @Test
    void wantJsonObjectMode_defaultsAndFlags() {
        assertTrue(LangChain4jScriptPlanAdapter.wantJsonObjectMode(null));
        assertTrue(LangChain4jScriptPlanAdapter.wantJsonObjectMode("auto"));
        assertTrue(LangChain4jScriptPlanAdapter.wantJsonObjectMode("json"));
        assertFalse(LangChain4jScriptPlanAdapter.wantJsonObjectMode("off"));
        assertFalse(LangChain4jScriptPlanAdapter.wantJsonObjectMode("none"));
    }
}
