package com.dwcode.okxbot.imggen.agent.step;

import com.dwcode.okxbot.imggen.config.ImgGenProperties;
import com.dwcode.okxbot.imggen.enums.ImgGenTaskStatus;
import com.dwcode.okxbot.imggen.port.PromptEnhancePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@Slf4j
@Component
@RequiredArgsConstructor
public class EnhanceStep implements PipelineStep {

    private final PromptEnhancePort promptEnhancePort;
    private final ImgGenProperties properties;

    @Override
    public String name() {
        return "enhance";
    }

    @Override
    public ImgGenTaskStatus runningStatus() {
        return ImgGenTaskStatus.PROMPT_ENHANCING;
    }

    @Override
    public String stepLabel() {
        return "正在润色提示词";
    }

    @Override
    public int progressPercent() {
        return 25;
    }

    public boolean shouldRun(PipelineContext ctx) {
        if (properties.isMockPipeline()) {
            return false;
        }
        String mode = properties.getSteps().getEnhance();
        if (mode == null || "off".equalsIgnoreCase(mode)) {
            return false;
        }
        return ctx.getTask().getEnhanceEnabled() != null && ctx.getTask().getEnhanceEnabled() == 1;
    }

    @Override
    public void execute(PipelineContext ctx) throws Exception {
        if (ctx.getCancelCheck() != null && ctx.getCancelCheck().getAsBoolean()) {
            throw new InterruptedException("cancelled");
        }
        String mode = properties.getSteps().getEnhance();
        if ("mock".equalsIgnoreCase(mode) || properties.isMockPipeline()) {
            if (properties.getMockStepDelayMs() > 0) {
                Thread.sleep(properties.getMockStepDelayMs());
            }
            String enhanced = "[enhanced] " + ctx.getTask().getPrompt();
            ctx.setFinalPrompt(enhanced);
            ctx.getTask().setEnhancedPrompt(enhanced);
            return;
        }

        try {
            // languageHint 仅作偏好；适配器会与用户原文同语言润色，不强制英文化
            String enhanced = promptEnhancePort.enhance(
                    ctx.getTask().getPrompt(),
                    "auto",
                    ctx.getTask().getLlmProvider(),
                    ctx.getTask().getLlmModel()
            );
            ctx.setFinalPrompt(enhanced);
            ctx.getTask().setEnhancedPrompt(enhanced);
            if (ctx.getWorkDir() != null) {
                Files.writeString(ctx.getWorkDir().resolve("prompt.enhanced.txt"),
                        enhanced, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            if (properties.getPromptEnhance().isFallbackOnError()) {
                log.warn("Prompt 润色失败，降级使用原文: {}", e.getMessage());
                ctx.setFinalPrompt(ctx.getTask().getPrompt());
                ctx.getTask().setEnhancedPrompt(null);
            } else {
                throw e;
            }
        }
    }
}
