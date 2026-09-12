package com.dwcode.okxbot.chat.agent.tools;

import com.dwcode.okxbot.chat.agent.AgentTool;
import com.dwcode.okxbot.chat.agent.ToolContext;
import com.dwcode.okxbot.chat.agent.ToolResult;
import com.dwcode.okxbot.chat.agent.ToolRisk;
import com.dwcode.okxbot.rag.search.HybridHit;
import com.dwcode.okxbot.rag.search.HybridSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在当前用户知识库中搜索笔记与文档（READ，混合检索）。
 */
@Component
@RequiredArgsConstructor
public class SearchNotesTool implements AgentTool {

    private final HybridSearchService hybridSearch;

    @Override
    public String name() {
        return "search_notes";
    }

    @Override
    public String description() {
        return "在当前用户的个人知识库中搜索笔记和附件文档。"
                + "参数 args.keyword 必填（语义或关键词均可）；"
                + "args.limit 可选 1-20，默认 8。";
    }

    @Override
    public ToolRisk risk() {
        return ToolRisk.READ;
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String keyword = str(args, "keyword", "").trim();
        if (keyword.isBlank()) {
            return ToolResult.fail("BAD_ARGS", "缺少 args.keyword（搜索关键词）");
        }
        if (keyword.length() > 100) {
            keyword = keyword.substring(0, 100);
        }
        int limit = intArg(args, "limit", 8);
        limit = Math.max(1, Math.min(20, limit));

        List<HybridHit> hits = hybridSearch.search(ctx.getUserId(), keyword, limit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (HybridHit h : hits) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", h.getNoteId() > 0 ? String.valueOf(h.getNoteId()) : null);
            row.put("title", h.getTitle());
            row.put("snippet", h.getSnippet());
            row.put("sourceType", h.getSourceType());
            row.put("fileName", h.getFileName());
            row.put("kind", h.getKind());
            row.put("openPath", "/kb");
            items.add(row);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("keyword", keyword);
        data.put("count", items.size());
        data.put("total", items.size());
        data.put("items", items);

        Map<String, Object> ui = new HashMap<>();
        ui.put("type", "note_list");
        ui.put("payload", data);

        String msg = items.isEmpty()
                ? "知识库中未找到与「" + keyword + "」相关的笔记或文档。"
                : "找到 " + items.size() + " 条相关笔记/文档。";
        return ToolResult.success(msg, data, ui);
    }

    private static String str(Map<String, Object> args, String key, String def) {
        if (args == null || args.get(key) == null) {
            return def;
        }
        return String.valueOf(args.get(key));
    }

    private static int intArg(Map<String, Object> args, String key, int def) {
        if (args == null || args.get(key) == null) {
            return def;
        }
        Object v = args.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }
}
