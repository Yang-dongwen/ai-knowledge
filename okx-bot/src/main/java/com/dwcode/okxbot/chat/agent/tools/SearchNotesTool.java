package com.dwcode.okxbot.chat.agent.tools;

import com.dwcode.okxbot.chat.agent.AgentTool;
import com.dwcode.okxbot.chat.agent.ToolContext;
import com.dwcode.okxbot.chat.agent.ToolResult;
import com.dwcode.okxbot.chat.agent.ToolRisk;
import com.dwcode.okxbot.kb.dto.NotePageResponse;
import com.dwcode.okxbot.kb.dto.NoteResponse;
import com.dwcode.okxbot.kb.service.KbNoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在当前用户知识库中搜索笔记（READ）。
 */
@Component
@RequiredArgsConstructor
public class SearchNotesTool implements AgentTool {

    private final KbNoteService noteService;

    @Override
    public String name() {
        return "search_notes";
    }

    @Override
    public String description() {
        return "在当前用户的个人知识库中搜索笔记。"
                + "参数 args.keyword 必填（标题/正文关键词）；"
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

        NotePageResponse page = noteService.list(
                0, limit, null, null, keyword, false, false, false, false);
        List<Map<String, Object>> items = new ArrayList<>();
        if (page.getItems() != null) {
            for (NoteResponse n : page.getItems()) {
                Map<String, Object> row = new HashMap<>();
                row.put("id", n.getId() != null ? String.valueOf(n.getId()) : null);
                row.put("title", n.getTitle());
                row.put("snippet", n.getMatchSnippet() != null ? n.getMatchSnippet() : n.getSnippet());
                row.put("pinned", n.isPinned());
                row.put("categoryName", n.getCategoryName());
                row.put("updatedAt", n.getUpdatedAt() != null ? n.getUpdatedAt().toString() : null);
                row.put("openPath", "/kb");
                items.add(row);
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("keyword", keyword);
        data.put("count", items.size());
        data.put("total", page.getTotal());
        data.put("items", items);

        Map<String, Object> ui = new HashMap<>();
        ui.put("type", "note_list");
        ui.put("payload", data);

        String msg = items.isEmpty()
                ? "知识库中未找到与「" + keyword + "」相关的笔记。"
                : "找到 " + items.size() + " 条相关笔记（共 " + page.getTotal() + "）。";
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
