package com.dwcode.okxbot.chat.agent.graph;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentGraphFactory {

    private final RetrieveNode retrieveNode;
    private final DecideNode decideNode;
    private final ExecuteToolNode executeToolNode;
    private final SummarizeNode summarizeNode;

    private CompiledGraph<ChatAgentState> compiled;

    @PostConstruct
    void init() throws GraphStateException {
        StateGraph<ChatAgentState> graph = new StateGraph<>(ChatAgentState.SCHEMA, ChatAgentState::new)
                .addNode("retrieve", node_async(retrieveNode))
                .addNode("decide", node_async(decideNode))
                .addNode("execute", node_async(executeToolNode))
                .addNode("summarize", node_async(summarizeNode))
                .addEdge(START, "retrieve")
                .addEdge("retrieve", "decide")
                .addConditionalEdges("decide", edge_async(this::afterDecide), Map.of(
                        "read", "execute",
                        "write", "execute",
                        "reply", END,
                        "fallback", END,
                        "unknown", END
                ))
                .addConditionalEdges("execute", edge_async(this::afterExecute), Map.of(
                        "summarize", "summarize",
                        "confirm", END,
                        "fail", END
                ))
                .addEdge("summarize", END);
        this.compiled = graph.compile();
        log.info("LangGraph4j Agent 图已编译 nodes=retrieve,decide,execute,summarize");
    }

    public CompiledGraph<ChatAgentState> compiled() {
        return compiled;
    }

    private String afterDecide(ChatAgentState state) {
        String r = state.route();
        if (r == null || r.isBlank()) {
            return "fallback";
        }
        return r;
    }

    private String afterExecute(ChatAgentState state) {
        String r = state.route();
        if ("summarize".equals(r)) {
            return "summarize";
        }
        if ("confirm".equals(r)) {
            return "confirm";
        }
        return "fail";
    }
}
