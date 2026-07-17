package com.dwcode.okxbot.chat.agent;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 白名单工具注册表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final List<AgentTool> tools;
    private final Map<String, AgentTool> byName = new LinkedHashMap<>();

    @PostConstruct
    void init() {
        byName.clear();
        if (tools != null) {
            for (AgentTool t : tools) {
                if (t == null || t.name() == null || t.name().isBlank()) {
                    continue;
                }
                byName.put(t.name().trim(), t);
                log.info("注册 Agent Tool: name={}, risk={}", t.name(), t.risk());
            }
        }
        log.info("Agent Tool 注册完成: count={}", byName.size());
    }

    public AgentTool get(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return byName.get(name.trim());
    }

    public Collection<AgentTool> all() {
        return byName.values();
    }

    /** 拼给决策模型的工具清单文本（含 READ + WRITE 草案） */
    public String describeForPrompt() {
        StringBuilder sb = new StringBuilder();
        for (AgentTool t : byName.values()) {
            sb.append("- ").append(t.name())
                    .append(" [").append(t.risk()).append("]")
                    .append(": ").append(t.description()).append('\n');
        }
        return sb.toString();
    }
}
