package com.dwcode.okxbot.chat.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentJsonParserTest {

    private final IntentJsonParser parser = new IntentJsonParser();

    @Test
    void parse_toolWithArgs() {
        String raw = "{\"tool\":\"list_my_tasks\",\"args\":{\"type\":\"imggen\",\"limit\":10}}";
        IntentJsonParser.AgentIntent intent = parser.parse(raw);
        assertTrue(intent.isParsed());
        assertEquals("list_my_tasks", intent.getTool());
        assertEquals("imggen", intent.getArgs().get("type"));
        assertEquals(10L, intent.getArgs().get("limit"));
    }

    @Test
    void parse_nullToolWithReply() {
        String raw = "{\"tool\":null,\"reply\":\"你好，我是助手\"}";
        IntentJsonParser.AgentIntent intent = parser.parse(raw);
        assertTrue(intent.isParsed());
        assertNull(intent.getTool());
        assertEquals("你好，我是助手", intent.getReply());
    }

    @Test
    void parse_markdownFence() {
        String raw = "好的，调用工具：\n```json\n{\"tool\":\"get_task\",\"args\":{\"type\":\"video\",\"taskId\":\"1\"}}\n```\n";
        IntentJsonParser.AgentIntent intent = parser.parse(raw);
        assertTrue(intent.isParsed());
        assertEquals("get_task", intent.getTool());
        assertEquals("1", intent.getArgs().get("taskId"));
    }

    @Test
    void parse_embeddedJson() {
        String raw = "意图如下 {\"tool\":\"draft_imggen\",\"args\":{\"prompt\":\"a cat\"}} 结束";
        IntentJsonParser.AgentIntent intent = parser.parse(raw);
        assertTrue(intent.isParsed());
        assertEquals("draft_imggen", intent.getTool());
        assertEquals("a cat", intent.getArgs().get("prompt"));
    }

    @Test
    void parse_blankToolTreatedAsNull() {
        String raw = "{\"tool\":\"  \",\"reply\":\"闲聊\"}";
        IntentJsonParser.AgentIntent intent = parser.parse(raw);
        assertTrue(intent.isParsed());
        assertNull(intent.getTool());
        assertEquals("闲聊", intent.getReply());
    }

    @Test
    void parse_failureFallsBackToRawText() {
        String raw = "这不是 JSON，纯文本回复";
        IntentJsonParser.AgentIntent intent = parser.parse(raw);
        assertFalse(intent.isParsed());
        assertNull(intent.getTool());
        assertEquals(raw, intent.getReply());
    }

    @Test
    void parse_nullOrBlank() {
        assertFalse(parser.parse(null).isParsed());
        assertFalse(parser.parse("   ").isParsed());
        assertNotNull(parser.parse("").getArgs());
    }
}
