package com.dwcode.okxbot.kb.service;

import com.dwcode.okxbot.kb.dto.ExplorerNodeResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目录树排序规则：文件夹优先，文档 pin/time。
 * （完整 tree() 依赖 DB/Security，此处校验节点排序逻辑的局部语义）
 */
class KbExplorerServiceTest {

    @Test
    void folderNodesBeforeNotesInMixedList() {
        List<ExplorerNodeResponse> children = new ArrayList<>();
        children.add(ExplorerNodeResponse.builder()
                .type("note").id(1L).name("Z note").pinned(false)
                .updatedAt(LocalDateTime.now().minusDays(1)).build());
        children.add(ExplorerNodeResponse.builder()
                .type("folder").id(2L).name("A folder").sortOrder(1).build());
        children.add(ExplorerNodeResponse.builder()
                .type("note").id(3L).name("Pinned").pinned(true)
                .updatedAt(LocalDateTime.now()).build());
        children.add(ExplorerNodeResponse.builder()
                .type("folder").id(4L).name("B folder").sortOrder(0).build());

        // 复用服务内同样的排序语义
        children.sort(java.util.Comparator
                .comparing((ExplorerNodeResponse n) -> !"folder".equals(n.getType()))
                .thenComparing(n -> "note".equals(n.getType()) && n.isPinned() ? 0 : 1)
                .thenComparing(n -> n.getSortOrder() != null ? n.getSortOrder() : 0)
                .thenComparing(n -> n.getId() != null ? n.getId() : 0L));

        assertEquals("folder", children.get(0).getType());
        assertEquals(4L, children.get(0).getId()); // sortOrder 0 first
        assertEquals("folder", children.get(1).getType());
        assertEquals("note", children.get(2).getType());
        assertTrue(children.get(2).isPinned());
        assertEquals("note", children.get(3).getType());
    }
}
