package com.dwcode.okxbot.chat.agent.tools;

import com.dwcode.okxbot.aigen.dto.AigenTaskPageResponse;
import com.dwcode.okxbot.aigen.dto.AigenTaskResponse;
import com.dwcode.okxbot.aigen.service.AigenTaskService;
import com.dwcode.okxbot.chat.agent.AgentTool;
import com.dwcode.okxbot.chat.agent.ToolContext;
import com.dwcode.okxbot.chat.agent.ToolResult;
import com.dwcode.okxbot.chat.agent.ToolRisk;
import com.dwcode.okxbot.chat.agent.dto.AgentTaskSummary;
import com.dwcode.okxbot.imggen.dto.ImgGenTaskPageResponse;
import com.dwcode.okxbot.imggen.dto.ImgGenTaskResponse;
import com.dwcode.okxbot.imggen.service.ImgGenTaskService;
import com.dwcode.okxbot.video.dto.VideoTaskPageResponse;
import com.dwcode.okxbot.video.dto.VideoTaskResponse;
import com.dwcode.okxbot.video.service.VideoProcessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 查询当前用户最近任务（video / imggen / aigen）。
 */
@Component
@RequiredArgsConstructor
public class ListMyTasksTool implements AgentTool {

    private final ImgGenTaskService imgGenTaskService;
    private final AigenTaskService aigenTaskService;
    private final VideoProcessService videoProcessService;

    @Override
    public String name() {
        return "list_my_tasks";
    }

    @Override
    public String description() {
        return "查询当前用户最近的任务列表。"
                + "参数 args.type 可选: video(视频提取)|imggen(文生图)|aigen(视频生成)|all(默认全部)；"
                + "args.limit 可选 1-20，默认 5。";
    }

    @Override
    public ToolRisk risk() {
        return ToolRisk.READ;
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String type = strArg(args, "type", "all").toLowerCase(Locale.ROOT);
        int limit = intArg(args, "limit", 5);
        limit = Math.max(1, Math.min(20, limit));

        List<AgentTaskSummary> items = new ArrayList<>();
        if ("all".equals(type) || "imggen".equals(type) || "image".equals(type)) {
            items.addAll(listImggen(limit));
        }
        if ("all".equals(type) || "aigen".equals(type) || "video_gen".equals(type)) {
            items.addAll(listAigen(limit));
        }
        if ("all".equals(type) || "video".equals(type) || "extract".equals(type)) {
            items.addAll(listVideo(limit));
        }

        // all 时按时间粗排：字符串时间不够稳，保持各类型各自顺序后截断
        if ("all".equals(type) && items.size() > limit * 3) {
            items = items.subList(0, Math.min(items.size(), limit * 3));
        } else if (!"all".equals(type) && items.size() > limit) {
            items = items.subList(0, limit);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("type", type);
        data.put("count", items.size());
        data.put("items", items);

        Map<String, Object> ui = new HashMap<>();
        ui.put("type", "task_list");
        ui.put("payload", data);

        String msg = items.isEmpty()
                ? "未找到相关任务。"
                : "共找到 " + items.size() + " 条任务。";
        return ToolResult.success(msg, data, ui);
    }

    private List<AgentTaskSummary> listImggen(int limit) {
        ImgGenTaskPageResponse page = imgGenTaskService.listTasks(0, limit, null);
        List<AgentTaskSummary> list = new ArrayList<>();
        if (page.getItems() == null) {
            return list;
        }
        for (ImgGenTaskResponse t : page.getItems()) {
            list.add(AgentTaskSummary.builder()
                    .type("imggen")
                    .taskId(t.getId())
                    .status(t.getStatus())
                    .title(t.getTitle())
                    .prompt(truncate(t.getPrompt(), 80))
                    .errorMessage(t.getErrorMessage())
                    .resultHint(Boolean.TRUE.equals(t.getOutputAvailable()) ? "图片已生成" : null)
                    .openPath("/image-generate")
                    .createdAt(t.getCreatedAt())
                    .updatedAt(t.getUpdatedAt())
                    .progress(t.getProgress())
                    .build());
        }
        return list;
    }

    private List<AgentTaskSummary> listAigen(int limit) {
        AigenTaskPageResponse page = aigenTaskService.listTasks(0, limit, null);
        List<AgentTaskSummary> list = new ArrayList<>();
        if (page.getItems() == null) {
            return list;
        }
        for (AigenTaskResponse t : page.getItems()) {
            list.add(AgentTaskSummary.builder()
                    .type("aigen")
                    .taskId(t.getId())
                    .status(t.getStatus())
                    .title(t.getTitle())
                    .prompt(truncate(t.getPrompt(), 80))
                    .errorMessage(t.getErrorMessage())
                    .resultHint(Boolean.TRUE.equals(t.getOutputAvailable()) ? "视频已生成" : null)
                    .openPath("/video-generate")
                    .createdAt(t.getCreatedAt())
                    .updatedAt(t.getUpdatedAt())
                    .progress(t.getProgress())
                    .build());
        }
        return list;
    }

    private List<AgentTaskSummary> listVideo(int limit) {
        VideoTaskPageResponse page = videoProcessService.listTasks(0, limit);
        List<AgentTaskSummary> list = new ArrayList<>();
        if (page.getItems() == null) {
            return list;
        }
        for (VideoTaskResponse t : page.getItems()) {
            list.add(AgentTaskSummary.builder()
                    .type("video")
                    .taskId(t.getTaskId())
                    .status(t.getStatus())
                    .title(t.getTitle() != null ? t.getTitle() : t.getUrl())
                    .prompt(truncate(t.getUrl(), 80))
                    .errorMessage(t.getErrorMessage())
                    .resultHint(t.getResult() != null ? "摘要可用" : null)
                    .openPath("/video-extract")
                    .createdAt(t.getCreatedAt())
                    .updatedAt(t.getFinishedAt() != null ? t.getFinishedAt() : t.getCreatedAt())
                    .progress(null)
                    .build());
        }
        return list;
    }

    private static String strArg(Map<String, Object> args, String key, String def) {
        if (args == null || !args.containsKey(key) || args.get(key) == null) {
            return def;
        }
        return String.valueOf(args.get(key));
    }

    private static int intArg(Map<String, Object> args, String key, int def) {
        if (args == null || !args.containsKey(key) || args.get(key) == null) {
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

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
