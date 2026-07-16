package com.dwcode.okxbot.aigen.agent.step;

import com.dwcode.okxbot.aigen.domain.StoryboardDto;
import com.dwcode.okxbot.aigen.domain.shot.ShotlistDto;
import com.dwcode.okxbot.aigen.enums.AigenTaskStatus;
import com.dwcode.okxbot.aigen.port.DirectorCommand;
import com.dwcode.okxbot.aigen.port.DirectorPort;
import com.dwcode.okxbot.aigen.port.PlanCommand;
import com.dwcode.okxbot.aigen.port.ScriptPlanPort;
import com.dwcode.okxbot.aigen.service.AigenStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
@RequiredArgsConstructor
public class PlanStep implements PipelineStep {

    private final ScriptPlanPort scriptPlanPort;
    private final DirectorPort directorPort;
    private final AigenStorageService storageService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "plan";
    }

    @Override
    public AigenTaskStatus runningStatus() {
        return AigenTaskStatus.PLANNING;
    }

    @Override
    public String stepLabel() {
        return "正在规划分镜/镜头表";
    }

    @Override
    public int progressPercent() {
        return 20;
    }

    @Override
    public void execute(PipelineContext ctx) throws Exception {
        var task = ctx.getTask();
        if (ctx.isVisualMode()) {
            executeVisual(ctx);
        } else {
            executeTemplate(ctx);
        }
        storageService.writeRequestSnapshot(ctx.getWorkDir(), task.getPrompt(), task.getTemplateId());
    }

    private void executeVisual(PipelineContext ctx) throws Exception {
        var task = ctx.getTask();
        ShotlistDto list = directorPort.plan(DirectorCommand.builder()
                .prompt(task.getPrompt())
                .language(task.getLanguage())
                .aspectRatio(task.getAspectRatio())
                .targetDurationSec(task.getTargetDurationSec() != null ? task.getTargetDurationSec() : 30)
                .stylePreset(task.getStylePreset())
                .audioMode(task.getAudioMode())
                .llmProvider(task.getLlmProvider())
                .llmModel(task.getLlmModel())
                .titleHint(task.getTitle())
                .build());
        ctx.setShotlist(list);

        Path planPath = ctx.getWorkDir().resolve("shotlist.plan.json");
        Path sbPath = ctx.getWorkDir().resolve("shotlist.json");
        // 兼容：同时写 storyboard.json 别名供排查
        Path alias = ctx.getWorkDir().resolve("storyboard.json");
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(list);
        Files.writeString(planPath, json);
        Files.writeString(sbPath, json);
        Files.writeString(alias, json);

        task.setStoryboardJson(json);
        task.setStoryboardPath(sbPath.toAbsolutePath().toString());
        task.setShotCount(list.getShots() != null ? list.getShots().size() : 0);
        task.setAssetDoneCount(0);
        if (list.getMeta() != null && list.getMeta().getTitle() != null) {
            task.setTitle(list.getMeta().getTitle());
        }
        task.setCurrentStep("镜头表已规划（" + task.getShotCount() + " 镜）");
    }

    private void executeTemplate(PipelineContext ctx) throws Exception {
        var task = ctx.getTask();
        PlanCommand cmd = PlanCommand.builder()
                .prompt(task.getPrompt())
                .templateId(task.getTemplateId())
                .language(task.getLanguage())
                .aspectRatio(task.getAspectRatio())
                .targetDurationSec(task.getTargetDurationSec() != null ? task.getTargetDurationSec() : 30)
                .llmProvider(task.getLlmProvider())
                .llmModel(task.getLlmModel())
                .titleHint(task.getTitle())
                .build();

        StoryboardDto sb = scriptPlanPort.plan(cmd);
        ctx.setStoryboard(sb);

        Path planPath = ctx.getWorkDir().resolve("storyboard.plan.json");
        Path sbPath = ctx.getWorkDir().resolve("storyboard.json");
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(sb);
        Files.writeString(planPath, json);
        Files.writeString(sbPath, json);

        task.setStoryboardJson(json);
        task.setStoryboardPath(sbPath.toAbsolutePath().toString());
        if (sb.getMeta() != null && sb.getMeta().getTitle() != null) {
            task.setTitle(sb.getMeta().getTitle());
        }
    }
}
