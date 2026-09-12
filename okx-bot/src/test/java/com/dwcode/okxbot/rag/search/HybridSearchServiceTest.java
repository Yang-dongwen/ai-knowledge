package com.dwcode.okxbot.rag.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridSearchServiceTest {

    @Test
    void rrfMergesSameNote() {
        HybridHit a = HybridHit.builder().noteId(1).sourceType("note").title("A").snippet("kw").build();
        HybridHit b = HybridHit.builder().noteId(1).sourceType("note").title("A").snippet("vector").build();
        HybridHit c = HybridHit.builder().noteId(2).sourceType("note").title("B").snippet("other").build();
        List<HybridHit> merged = HybridSearchService.rrf(List.of(a, c), List.of(b), 5, 60);
        assertEquals(2, merged.size());
        assertEquals(1L, merged.get(0).getNoteId());
    }

    @Test
    void rrfKeepsUnique() {
        HybridHit a = HybridHit.builder().noteId(1).sourceType("note").title("A").build();
        HybridHit b = HybridHit.builder().noteId(2).sourceType("file").fileId(9L).title("B").build();
        List<HybridHit> merged = HybridSearchService.rrf(List.of(a), List.of(b), 5, 60);
        assertEquals(2, merged.size());
    }
}
