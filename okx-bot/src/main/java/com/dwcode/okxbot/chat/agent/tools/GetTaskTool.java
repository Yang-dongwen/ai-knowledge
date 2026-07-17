package com.dwcode.okxbot.chat.agent.tools;

import com.dwcode.okxbot.aigen.dto.AigenTaskResponse;
import com.dwcode.okxbot.aigen.service.AigenTaskService;
import com.dwcode.okxbot.chat.agent.AgentTool;
import com.dwcode.okxbot.chat.agent.ToolContext;
import com.dwcode.okxbot.chat.agent.ToolResult;
import com.dwcode.okxbot.chat.agent.ToolRisk;
import com.dwcode.okxbot.chat.agent.dto.AgentTaskSummary;
import com.dwcode.okxbot.imggen.dto.ImgGenTaskResponse;
import com.dwcode.okxbot.imggen.service.ImgGenTaskService;
import com.dwcode.okxbot.video.dto.VideoTaskResponse;
import com.dwcode.okxbot.video.service.VideoProcessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 查询单条任务详情（强制归属当前用户，Service 内已校验）。
 */
@Component
@RequiredArgsConstructor
public class GetTaskTool implements AgentTool {

    private final ImgGenTaskService imgGenTaskService;
    private final AigenTaskService aigenTaskService;
    private final VideoProcessService videoProcessService;

    @Override
    public String name() {
        return "get_task";
    }

    @Override
    public String description() {
        return "查询当前用户某一任务详情。"
                + "参数 args.type 必填: video|imggen|aigen；"
                + "args.taskId 必填: 任务 ID。";
    }

    @Override
    public ToolRisk risk() {
        return ToolRisk.READ;
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String type = strArg(args, "type", "").toLowerCase(Locale.ROOT);
        String taskIdStr = strArg(args, "taskId", null);
        if (taskIdStr == null || taskIdStr.isBlank()) {
            taskIdStr = strArg(args, "id", null);
        }
        if (type.isBlank()) {
            return ToolResult.fail("BAD_ARGS", "缺少 args.type（video|imggen|aigen）");
        }
        if (taskIdStr == null || taskIdStr.isBlank()) {
            return ToolResult.fail("BAD_ARGS", "缺少 args.taskId");
        }
        Long taskId;
        try {
            taskId = Long.parseLong(taskIdStr.trim());
        } catch (Exception e) {
            return ToolResult.fail("BAD_ARGS", "taskId 无效");
        }

        AgentTaskSummary summary;
        try {
            summary = switch (type) {
                case "imggen", "image" -> mapImggen(imgGenTaskService.getTask(taskId));
                case "aigen", "video_gen" -> mapAigen(aigenTaskService.getTask(taskId));
                case "video", "extract" -> mapVideo(videoProcessService.getStatus(taskId));
                default -> null;
            };
        } catch (Exception e) {
            return ToolResult.fail("NOT_FOUND", e.getMessage() != null ? e.getMessage() : "任务不存在或无权限");
        }
        if (summary == null) {
            return ToolResult.fail("BAD_ARGS", "type 须为 video|imggen|aigen");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("task", summary);

        Map<String, Object> ui = new HashMap<>();
        ui.put("type", "task_status");
        ui.put("payload", data);

        return ToolResult.success(
                "任务 " + summary.getTaskId() + " 状态: " + summary.getStatus(),
                data,
                ui);
    }

    private static AgentTaskSummary mapImggen(ImgGenTaskResponse t) {
        return AgentTaskSummary.builder()
                .type("imggen")
                .taskId(t.getId())
                .status(t.getStatus())
                .title(t.getTitle())
                .prompt(t.getPrompt())
                .errorMessage(t.getErrorMessage())
                .resultHint(Boolean.TRUE.equals(t.getOutputAvailable()) ? "图片已生成" : t.getCurrentStep())
                .openPath("/image-generate")
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .progress(t.getProgress())
                .build();
    }

    private static AgentTaskSummary mapAigen(AigenTaskResponse t) {
        return AgentTaskSummary.builder()
                .type("aigen")
                .taskId(t.getId())
                .status(t.getStatus())
                .title(t.getTitle())
                .prompt(t.getPrompt())
                .errorMessage(t.getErrorMessage())
                .resultHint(Boolean.TRUE.equals(t.getOutputAvailable()) ? "视频已生成" : t.getCurrentStep())
                .openPath("/video-generate")
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .progress(t.getProgress())
                .build();
    }

    private static AgentTaskSummary mapVideo(VideoTaskResponse t) {
        return AgentTaskSummary.builder()
                .type("video")
                .taskId(t.getTaskId())
                .status(t.getStatus())
                .title(t.getTitle() != null ? t.getTitle() : t.getUrl())
                .prompt(t.getUrl())
                .errorMessage(t.getErrorMessage())
                .resultHint(t.getResult() != null ? "摘要可用" : t.getCurrentStep())
                .openPath("/video-extract")
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getFinishedAt() != null ? t.getFinishedAt() : t.getCreatedAt())
                .build();
    }

    private static String strArg(Map<String, Object> args, String key, String def) {
        if (args == null || !args.containsKey(key) || args.get(key) == null) {
            return def;
        }
        return String.valueOf(args.get(key));
    }
}
