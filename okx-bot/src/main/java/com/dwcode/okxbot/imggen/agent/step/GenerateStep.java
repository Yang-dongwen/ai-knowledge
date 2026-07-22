package com.dwcode.okxbot.imggen.agent.step;

import com.dwcode.okxbot.imggen.config.ImgGenProperties;
import com.dwcode.okxbot.imggen.enums.ImgGenTaskStatus;
import com.dwcode.okxbot.imggen.port.ImageAsset;
import com.dwcode.okxbot.imggen.port.ImageGenCommand;
import com.dwcode.okxbot.imggen.port.ImageGenPort;
import com.dwcode.okxbot.imggen.port.ImageGenResult;
import com.dwcode.okxbot.imggen.util.AspectRatioMapper;
import com.dwcode.okxbot.common.ai.AiModelConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GenerateStep implements PipelineStep {

    private final ImageGenPort imageGenPort;
    private final ImgGenProperties properties;
    private final AiModelConfigService aiModelConfigService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "generate";
    }

    @Override
    public ImgGenTaskStatus runningStatus() {
        return ImgGenTaskStatus.GENERATING;
    }

    @Override
    public String stepLabel() {
        return "正在生成图片";
    }

    @Override
    public int progressPercent() {
        return 70;
    }

    @Override
    public void execute(PipelineContext ctx) throws Exception {
        if (ctx.getCancelCheck() != null && ctx.getCancelCheck().getAsBoolean()) {
            throw new InterruptedException("cancelled");
        }
        var task = ctx.getTask();
        String prompt = ctx.getFinalPrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = task.getPrompt();
        }

        Path outputs = ctx.getWorkDir().resolve("outputs");
        String modelId = task.getModel();
        String providerKey = task.getProvider();
        String invokeUrl = null;
        int defaultSteps = 4;
        boolean mockGen = properties.isMockPipeline()
                || "mock".equalsIgnoreCase(properties.getSteps().getGenerate());
        if (!mockGen) {
            var cfg = aiModelConfigService.requireEnabledImageModel(task.getProvider(), modelId);
            modelId = cfg.getModelId();
            providerKey = cfg.getProvider();
            invokeUrl = cfg.getInvokeUrl();
            if (cfg.getDefaultSteps() != null && cfg.getDefaultSteps() > 0) {
                defaultSteps = cfg.getDefaultSteps();
            }
        }

        String aspect = task.getAspectRatio() != null ? task.getAspectRatio() : "1:1";
        AspectRatioMapper.Size size = AspectRatioMapper.map(aspect);
        if (task.getWidth() == null || task.getHeight() == null
                || task.getWidth() != size.width() || task.getHeight() != size.height()) {
            log.info("校正分辨率: taskId={} aspect={} {}x{} → {}x{}",
                    task.getId(), aspect, task.getWidth(), task.getHeight(),
                    size.width(), size.height());
            task.setWidth(size.width());
            task.setHeight(size.height());
        }

        ImageGenCommand cmd = ImageGenCommand.builder()
                .taskId(String.valueOf(task.getId()))
                .prompt(prompt)
                .negativePrompt(task.getNegativePrompt())
                .modelId(modelId)
                .providerKey(providerKey)
                .invokeUrl(invokeUrl)
                .width(size.width())
                .height(size.height())
                .steps(task.getSteps() != null ? task.getSteps() : defaultSteps)
                .n(task.getN() != null ? task.getN() : 1)
                .seed(task.getSeed())
                .workDir(ctx.getWorkDir())
                .outputsDir(outputs)
                .build();

        ImageGenResult result = imageGenPort.generate(cmd);
        ctx.setImages(result.getImages());

        if (result.getProviderRequestId() != null) {
            task.setProviderRequestId(result.getProviderRequestId());
        }

        Map<String, Object> resultMap = new LinkedHashMap<>();
        List<Map<String, Object>> images = new ArrayList<>();
        for (ImageAsset a : result.getImages()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", a.getIndex());
            m.put("path", a.getRelativePath());
            m.put("width", a.getWidth());
            m.put("height", a.getHeight());
            m.put("seed", a.getSeed());
            images.add(m);
        }
        resultMap.put("images", images);
        resultMap.put("provider", task.getProvider());
        resultMap.put("model", task.getModel());
        resultMap.put("latencyMs", result.getProviderLatencyMs());
        task.setResultJson(objectMapper.writeValueAsString(resultMap));

        if (!result.getImages().isEmpty()) {
            ImageAsset first = result.getImages().get(0);
            Path abs = ctx.getWorkDir().resolve(first.getRelativePath()).toAbsolutePath().normalize();
            task.setCoverPath(abs.toString());
        }
        log.info("文生图完成: taskId={} images={} latency={}ms",
                task.getId(), result.getImages().size(), result.getProviderLatencyMs());
    }
}
