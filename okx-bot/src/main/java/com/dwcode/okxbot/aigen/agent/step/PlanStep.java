package com.dwcode.okxbot.aigen.agent.step;

import com.dwcode.okxbot.aigen.domain.StoryboardDto;
import com.dwcode.okxbot.aigen.enums.AigenTaskStatus;
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
        return "正在规划分镜脚本";
    }

    @Override
    public int progressPercent() {
        return 20;
    }

    @Override
    public void execute(PipelineContext ctx) throws Exception {
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
        storageService.writeRequestSnapshot(ctx.getWorkDir(), task.getPrompt(), task.getTemplateId());
    }
}
