package com.dwcode.okxbot.chat.agent.graph;

import com.dwcode.okxbot.chat.agent.ToolResult;
import com.dwcode.okxbot.rag.search.HybridHit;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.List;
import java.util.Map;

public class ChatAgentState extends AgentState {

    public static final String USER_MESSAGE = "userMessage";
    public static final String HISTORY = "historyDigest";
    public static final String USER_ID = "userId";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String STREAM_ID = "streamId";
    public static final String PROVIDER = "providerKey";
    public static final String MODEL = "modelId";
    public static final String RETRIEVED = "retrieved";
    public static final String TOOL = "tool";
    public static final String ARGS = "args";
    public static final String REPLY = "reply";
    public static final String PARSED = "parsed";
    public static final String TOOL_RESULT = "toolResult";
    public static final String NEEDS_CONFIRM = "needsConfirm";
    public static final String ASSISTANT = "assistantText";
    public static final String USED_TOOL = "usedTool";
    public static final String ROUTE = "route";

    public static final Map<String, Channel<?>> SCHEMA = Map.ofEntries(
            Map.entry(USER_MESSAGE, Channels.<String>base(() -> "")),
            Map.entry(HISTORY, Channels.<String>base(() -> "")),
            Map.entry(USER_ID, Channels.<Long>base(() -> null)),
            Map.entry(CONVERSATION_ID, Channels.<Long>base(() -> null)),
            Map.entry(STREAM_ID, Channels.<String>base(() -> "")),
            Map.entry(PROVIDER, Channels.<String>base(() -> "")),
            Map.entry(MODEL, Channels.<String>base(() -> "")),
            Map.entry(RETRIEVED, Channels.<List<HybridHit>>base(() -> List.of())),
            Map.entry(TOOL, Channels.<String>base(() -> null)),
            Map.entry(ARGS, Channels.<Map<String, Object>>base(() -> Map.of())),
            Map.entry(REPLY, Channels.<String>base(() -> null)),
            Map.entry(PARSED, Channels.<Boolean>base(() -> Boolean.FALSE)),
            Map.entry(TOOL_RESULT, Channels.<Object>base(() -> null)),
            Map.entry(NEEDS_CONFIRM, Channels.<Boolean>base(() -> Boolean.FALSE)),
            Map.entry(ASSISTANT, Channels.<String>base(() -> null)),
            Map.entry(USED_TOOL, Channels.<Boolean>base(() -> Boolean.FALSE)),
            Map.entry(ROUTE, Channels.<String>base(() -> "fallback"))
    );

    public ChatAgentState(Map<String, Object> initData) {
        super(initData);
    }

    public String userMessage() {
        return str(USER_MESSAGE);
    }

    public String historyDigest() {
        return str(HISTORY);
    }

    public Long userId() {
        return (Long) value(USER_ID).orElse(null);
    }

    public Long conversationId() {
        return (Long) value(CONVERSATION_ID).orElse(null);
    }

    public String streamId() {
        return str(STREAM_ID);
    }

    public String providerKey() {
        return str(PROVIDER);
    }

    public String modelId() {
        return str(MODEL);
    }

    @SuppressWarnings("unchecked")
    public List<HybridHit> retrieved() {
        Object v = value(RETRIEVED).orElse(List.of());
        if (v instanceof List<?> list) {
            return (List<HybridHit>) list;
        }
        return List.of();
    }

    public String tool() {
        Object v = value(TOOL).orElse(null);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> args() {
        Object v = value(ARGS).orElse(Map.of());
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    public String reply() {
        Object v = value(REPLY).orElse(null);
        return v == null ? null : String.valueOf(v);
    }

    public boolean parsed() {
        return Boolean.TRUE.equals(value(PARSED).orElse(false));
    }

    public ToolResult toolResult() {
        Object v = value(TOOL_RESULT).orElse(null);
        return v instanceof ToolResult tr ? tr : null;
    }

    public boolean needsConfirm() {
        return Boolean.TRUE.equals(value(NEEDS_CONFIRM).orElse(false));
    }

    public String assistantText() {
        Object v = value(ASSISTANT).orElse(null);
        return v == null ? null : String.valueOf(v);
    }

    public boolean usedTool() {
        return Boolean.TRUE.equals(value(USED_TOOL).orElse(false));
    }

    public String route() {
        return str(ROUTE);
    }

    private String str(String key) {
        Object v = value(key).orElse("");
        return v == null ? "" : String.valueOf(v);
    }
}
