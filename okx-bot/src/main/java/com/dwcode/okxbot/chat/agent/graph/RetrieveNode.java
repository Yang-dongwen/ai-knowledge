package com.dwcode.okxbot.chat.agent.graph;

import com.dwcode.okxbot.kb.config.KbProperties;
import com.dwcode.okxbot.rag.search.HybridHit;
import com.dwcode.okxbot.rag.search.HybridSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RetrieveNode implements NodeAction<ChatAgentState> {

    private final HybridSearchService hybridSearch;
    private final KbProperties kbProperties;

    @Override
    public Map<String, Object> apply(ChatAgentState state) {
        AgentTurnScope.throwIfCancelled();
        AgentTurnScope.emit("retrieving");
        if (state.userId() == null || !StringUtils.hasText(state.userMessage())) {
            return Map.of(ChatAgentState.RETRIEVED, List.<HybridHit>of());
        }
        try {
            int k = Math.max(1, kbProperties.getRag().getTopK());
            List<HybridHit> hits = hybridSearch.search(state.userId(), state.userMessage(), k);
            return Map.of(ChatAgentState.RETRIEVED, hits == null ? List.of() : hits);
        } catch (Exception e) {
            log.warn("retrieve 失败，继续决策: {}", e.getMessage());
            return Map.of(ChatAgentState.RETRIEVED, List.<HybridHit>of());
        }
    }
}
