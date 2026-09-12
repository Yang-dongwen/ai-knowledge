package com.dwcode.okxbot.chat.agent.graph;

import com.dwcode.okxbot.chat.agent.ChatAgentOrchestrator;
import com.dwcode.okxbot.chat.agent.ToolContext;
import com.dwcode.okxbot.chat.config.AgentProperties;
import org.bsc.langgraph4j.CompiledGraph;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentGraphFactoryTest {

    @Test
    void orchestratorDisabledReturnsEmpty() {
        AgentProperties props = new AgentProperties();
        props.setEnabled(false);
        AgentGraphFactory factory = mock(AgentGraphFactory.class);
        ChatAgentOrchestrator orch = new ChatAgentOrchestrator(props, factory);
        ChatAgentOrchestrator.AgentTurnResult r = orch.runTurn("hi", "", "nvidia", "x",
                ToolContext.builder().userId(1L).conversationId(2L).build());
        assertFalse(r.isUsedTool());
        assertNull(r.getAssistantText());
    }

    @Test
    @SuppressWarnings("unchecked")
    void invokeEmptyIsFallback() {
        AgentProperties props = new AgentProperties();
        props.setEnabled(true);
        AgentGraphFactory factory = mock(AgentGraphFactory.class);
        CompiledGraph<ChatAgentState> graph = mock(CompiledGraph.class);
        when(factory.compiled()).thenReturn(graph);
        when(graph.invoke(any(Map.class))).thenReturn(Optional.empty());
        ChatAgentOrchestrator orch = new ChatAgentOrchestrator(props, factory);
        ChatAgentOrchestrator.AgentTurnResult r = orch.runTurn("hi", "", "nvidia", "x",
                ToolContext.builder().userId(1L).build());
        assertFalse(r.isUsedTool());
        assertNull(r.getAssistantText());
        assertNotNull(r);
    }
}
