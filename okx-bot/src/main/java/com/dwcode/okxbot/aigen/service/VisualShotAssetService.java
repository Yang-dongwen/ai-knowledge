package com.dwcode.okxbot.aigen.service;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.domain.shot.ShotDto;
import com.dwcode.okxbot.aigen.domain.shot.ShotVisual;
import com.dwcode.okxbot.aigen.domain.shot.ShotlistDto;
import com.dwcode.okxbot.aigen.entity.AigenTaskEntity;
import com.dwcode.okxbot.common.ai.LlmCallOptions;
import com.dwcode.okxbot.common.ai.LlmChatClient;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.imggen.config.ImgGenProperties;
import com.dwcode.okxbot.imggen.port.ImageGenCommand;
import com.dwcode.okxbot.imggen.port.ImageGenPort;
import com.dwcode.okxbot.imggen.port.ImageGenResult;
import com.dwcode.okxbot.imggen.util.AspectRatioMapper;
import com.dwcode.okxbot.aigen.port.TtsCommand;
import com.dwcode.okxbot.aigen.port.TtsPort;
import com.dwcode.okxbot.aigen.port.TtsResult;
import com.dwcode.okxbot.video.service.AiModelConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Visual 单镜素材：出图 / 润色 prompt / TTS / 占位图。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisualShotAssetService {

    /**
     * 出图前润色：保持与用户/分镜原文相同语言，禁止强制英文化。
     */
    private static final String ENHANCE_SYSTEM = """
            你是短视频分镜出图（FLUX 等）的提示词润色专家。
            请把用户的画面描述改写成一条更清晰、更适合出图的提示词。

            规则：
            1. 必须与输入使用相同语言（中文进中文出，英文进英文出），禁止擅自翻译成另一种语言。
            2. 只输出润色后的提示词正文：不要引号、不要 markdown、不要解释。
            3. 保留核心主体与意图，可补充光影、构图、氛围、质感；画面中不要出现可读文字或水印。
            4. 控制在约 200 字以内（或英文约 120 词以内）。
            5. 不要加入 NSFW 内容。
            """;

    private final ImageGenPort imageGenPort;
    private final ImgGenProperties imgGenProperties;
    private final AiModelConfigService aiModelConfigService;
    private final AigenProperties aigenProperties;
    private final VisualPlaceholderImageService placeholderImageService;
    private final LlmChatClient llmChatClient;
    private final TtsPort ttsPort;
    private final AigenStorageService storageService;

    public AspectRatioMapper.Size resolveFluxSize(AigenTaskEntity task, ShotlistDto list) {
        String aspect = list.getMeta() != null && list.getMeta().getAspectRatio() != null
                ? list.getMeta().getAspectRatio()
                : (task.getAspectRatio() != null ? task.getAspectRatio() : "9:16");
        return AspectRatioMapper.map(aspect);
    }

    /**
     * 生成或刷新单镜主视觉；写 shot.visual.assetPath。
     *
     * @param enhancePrompt 是否先用 LLM 润色画面 prompt（保持原语言）
     */
    public void materializeShotImage(AigenTaskEntity task,
                                     Path workDir,
                                     ShotDto shot,
                                     AspectRatioMapper.Size size,
                                     int seedIndex,
                                     boolean mockAsset,
                                     boolean enhancePrompt) throws Exception {
        if (shot.getVisual() == null) {
            shot.setVisual(new ShotVisual());
        }
        String shotId = shot.getId() != null ? shot.getId() : ("shot-" + seedIndex);
        shot.setId(shotId);
        Path visualDir = workDir.resolve("assets").resolve("visual");
        Files.createDirectories(visualDir);
        Path outFile = visualDir.resolve(shotId + ".jpg");
        String rel = "assets/visual/" + shotId + ".jpg";
        String title = shot.getOverlay() != null ? shot.getOverlay().getTitle() : shotId;
        String type = shot.getVisual().getType() != null
                ? shot.getVisual().getType().toLowerCase(Locale.ROOT) : "ai_image";

        // 用户上传：已有路径且文件在则保留
        if ("user_image".equals(type) && shot.getVisual().getAssetPath() != null) {
            Path existing = workDir.resolve(shot.getVisual().getAssetPath()).normalize();
            if (Files.isRegularFile(existing) && existing.startsWith(workDir.normalize())) {
                return;
            }
        }

        String prompt = shot.getVisual().getPrompt();
        try {
            if (mockAsset || "solid".equals(type) || "gradient".equals(type)
                    || prompt == null || prompt.isBlank()) {
                placeholderImageService.writeGradientJpeg(
                        outFile, size.width(), size.height(),
                        title != null ? title : shotId, seedIndex);
                if (!"user_image".equals(type) && !"solid".equals(type)) {
                    // 保持 ai_image 或改为 gradient
                    if (prompt == null || prompt.isBlank()) {
                        shot.getVisual().setType("gradient");
                    }
                }
            } else if ("user_image".equals(type)) {
                placeholderImageService.writeGradientJpeg(
                        outFile, size.width(), size.height(),
                        title != null ? title : shotId, seedIndex);
                shot.getVisual().setType("gradient");
            } else {
                String finalPrompt = prompt;
                if (enhancePrompt && !mockAsset) {
                    finalPrompt = enhanceImagePrompt(task, prompt);
                    shot.getVisual().setPrompt(finalPrompt);
                }
                generateRealImage(task, workDir, shot, outFile, size.width(), size.height(), finalPrompt, seedIndex);
                shot.getVisual().setType("ai_image");
            }
            shot.getVisual().setAssetPath(rel);
        } catch (Exception e) {
            log.warn("镜头出图失败 shotId={}: {}", shotId, e.getMessage());
            if (aigenProperties.getVisual().isFailOnShotError()) {
                throw e;
            }
            placeholderImageService.writeGradientJpeg(
                    outFile, size.width(), size.height(),
                    title != null ? title : shotId, seedIndex);
            shot.getVisual().setAssetPath(rel);
            shot.getVisual().setType("gradient");
        }
    }

    public String enhanceImagePrompt(AigenTaskEntity task, String original) {
        try {
            String raw = llmChatClient.chat(
                    ENHANCE_SYSTEM,
                    "请润色以下画面提示词（保持原语言）：\n" + original,
                    task.getLlmProvider(),
                    task.getLlmModel(),
                    LlmCallOptions.builder()
                            .temperature(0.4)
                            .maxTokens(256)
                            .maxRetries(1)
                            .timeoutSeconds(60)
                            .build()
            );
            if (raw == null || raw.isBlank()) {
                return original;
            }
            String cleaned = raw.trim();
            if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() > 2) {
                cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
            }
            cleaned = cleaned
                    .replaceFirst("(?i)^(润色后(的)?(提示词)?|优化后|prompt)\\s*[:：]\\s*", "")
                    .trim();
            log.info("出图 prompt 润色: inLen={} outLen={}", original.length(), cleaned.length());
            return cleaned;
        } catch (Exception e) {
            log.warn("出图 prompt 润色失败，使用原文: {}", e.getMessage());
            return original;
        }
    }

    public void synthesizeShotNarration(AigenTaskEntity task,
                                        Path workDir,
                                        ShotDto shot,
                                        int fps) throws Exception {
        String text = resolveNarrationText(shot, task);
        // 回写，避免后续重试/排查仍为空
        if (shot.getNarration() == null || shot.getNarration().isBlank()) {
            shot.setNarration(text);
        }
        String shotId = shot.getId() != null ? shot.getId() : "shot";
        String rel = "assets/audio/" + shotId + ".mp3";
        Path out = storageService.resolveAsset(workDir, rel);
        Files.createDirectories(out.getParent());

        String voice = task.getVoiceId();
        if (voice == null || voice.isBlank()) {
            voice = aigenProperties.getTts().getDefaultVoice();
        }
        int fallbackFrames = shot.getDurationInFrames() != null ? shot.getDurationInFrames() : fps * 3;
        log.info("visual TTS: shotId={}, chars={}, preview={}",
                shotId, text.length(), text.length() > 40 ? text.substring(0, 40) + "…" : text);
        TtsResult result = ttsPort.synthesize(TtsCommand.builder()
                .sceneId(shotId)
                .text(text)
                .voiceId(voice)
                .language(task.getLanguage())
                .outputFile(out)
                .fallbackDurationFrames(fallbackFrames)
                .fps(fps)
                .build());
        String useRel = result.getRelativeSrc() != null ? result.getRelativeSrc() : rel;
        storageService.resolveAsset(workDir, useRel);
        shot.setAudioSrc(useRel);
        long durationMs = result.getDurationMs();
        if (durationMs > 0 && fps > 0) {
            int frames = Math.max(fallbackFrames,
                    (int) Math.ceil(durationMs / 1000.0 * fps)
                            + aigenProperties.getTts().getTailPaddingFrames());
            shot.setDurationInFrames(frames);
            shot.setDurationSec(frames / (double) fps);
        }
    }

    /**
     * 口播文案优先级：narration → overlay 标题/副标题/要点 → notes → 兜底短句。
     * 禁止把空串/" " 交给 Edge（sanitize 后会变空导致「引擎不可用」误报）。
     */
    static String resolveNarrationText(ShotDto shot, AigenTaskEntity task) {
        if (shot == null) {
            return defaultNarration(null, task);
        }
        if (shot.getNarration() != null && !shot.getNarration().isBlank()) {
            return shot.getNarration().trim();
        }
        StringBuilder sb = new StringBuilder();
        if (shot.getOverlay() != null) {
            appendPart(sb, shot.getOverlay().getTitle());
            appendPart(sb, shot.getOverlay().getSubtitle());
            if (shot.getOverlay().getBullets() != null) {
                for (String b : shot.getOverlay().getBullets()) {
                    appendPart(sb, b);
                }
            }
        }
        if (sb.isEmpty() && shot.getNotes() != null && !shot.getNotes().isBlank()) {
            appendPart(sb, shot.getNotes());
        }
        if (!sb.isEmpty()) {
            return sb.toString().trim();
        }
        return defaultNarration(shot, task);
    }

    private static void appendPart(StringBuilder sb, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        String p = part.trim();
        if (sb.length() > 0) {
            char last = sb.charAt(sb.length() - 1);
            if (last != '。' && last != '！' && last != '？' && last != '.' && last != '!' && last != '?') {
                sb.append('。');
            }
        }
        sb.append(p);
    }

    private static String defaultNarration(ShotDto shot, AigenTaskEntity task) {
        String order = shot != null && shot.getOrder() != null
                ? String.valueOf(shot.getOrder())
                : (shot != null && shot.getId() != null ? shot.getId() : "");
        String title = task != null && task.getTitle() != null && !task.getTitle().isBlank()
                ? task.getTitle().trim()
                : "本片";
        if (title.length() > 24) {
            title = title.substring(0, 24);
        }
        if (order.isBlank()) {
            return title + "，请看本镜画面。";
        }
        return title + "，第" + order + "镜。";
    }

    public void realignShotlistTimeline(ShotlistDto list) {
        if (list == null || list.getShots() == null) {
            return;
        }
        int fps = list.getMeta() != null && list.getMeta().getFps() != null
                ? list.getMeta().getFps() : 30;
        int frame = 0;
        for (ShotDto s : list.getShots()) {
            int df = s.getDurationInFrames() != null ? s.getDurationInFrames()
                    : (int) Math.round((s.getDurationSec() != null ? s.getDurationSec() : 3.5) * fps);
            df = Math.max(1, df);
            s.setDurationInFrames(df);
            s.setStartFrame(frame);
            s.setDurationSec(df / (double) fps);
            frame += df;
        }
        if (list.getMeta() != null) {
            list.getMeta().setDurationInFrames(frame);
        }
    }

    private void generateRealImage(AigenTaskEntity task, Path workDir, ShotDto shot,
                                   Path outFile, int width, int height,
                                   String prompt, int index) throws Exception {
        Path tmpOut = workDir.resolve("assets").resolve("visual").resolve("_tmp_" + index);
        Files.createDirectories(tmpOut);

        String provider = task.getImageProvider();
        String modelId = task.getImageModel();
        String invokeUrl = null;
        int steps = Math.max(1, aigenProperties.getVisual().getImageSteps());
        try {
            var cfg = aiModelConfigService.requireEnabledImageModel(provider, modelId);
            provider = cfg.getProvider();
            modelId = cfg.getModelId();
            invokeUrl = cfg.getInvokeUrl();
            if (cfg.getDefaultSteps() != null && cfg.getDefaultSteps() > 0) {
                steps = cfg.getDefaultSteps();
            }
            task.setImageProvider(provider);
            task.setImageModel(modelId);
        } catch (Exception e) {
            log.warn("任务级生图模型解析失败，回退 imggen.flux: {}", e.getMessage());
            if (provider == null || provider.isBlank()) {
                provider = aigenProperties.getVisual().getImageProviderKey();
            }
            if (provider == null || provider.isBlank()) {
                provider = "nvidia";
            }
            if (modelId == null || modelId.isBlank()) {
                modelId = imgGenProperties.getFlux() != null
                        ? imgGenProperties.getFlux().getDefaultModel() : "flux.1-schnell";
            }
            invokeUrl = imgGenProperties.getFlux() != null
                    ? imgGenProperties.getFlux().getInvokeUrl() : null;
        }

        String neg = shot.getVisual() != null ? shot.getVisual().getNegativePrompt() : null;
        if (neg == null || neg.isBlank()) {
            neg = "text, watermark, logo, blurry, low quality";
        }
        log.info("visual 出图: shot={} model={}/{} {}x{} steps={}",
                shot.getId(), provider, modelId, width, height, steps);
        ImageGenCommand cmd = ImageGenCommand.builder()
                .taskId(String.valueOf(task.getId()))
                .prompt(prompt)
                .negativePrompt(neg)
                .modelId(modelId)
                .providerKey(provider)
                .invokeUrl(invokeUrl)
                .width(width)
                .height(height)
                .steps(steps)
                .n(1)
                .seed(shot.getVisual() != null ? shot.getVisual().getSeed() : null)
                .workDir(workDir)
                .outputsDir(tmpOut)
                .build();
        ImageGenResult result = imageGenPort.generate(cmd);
        if (result.getImages() == null || result.getImages().isEmpty()) {
            throw new IllegalStateException("出图结果为空");
        }
        Path found = null;
        try (var stream = Files.list(tmpOut)) {
            found = stream.filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png");
            }).findFirst().orElse(null);
        }
        if (found == null) {
            Path alt = workDir.resolve(result.getImages().get(0).getRelativePath());
            if (Files.isRegularFile(alt)) {
                found = alt;
            }
        }
        if (found == null) {
            throw new IllegalStateException("找不到生成的图片文件");
        }
        Files.copy(found, outFile, StandardCopyOption.REPLACE_EXISTING);
    }

    public void saveUserImage(Path workDir, ShotDto shot, byte[] bytes, String originalFilename) throws Exception {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(400, "上传文件为空");
        }
        if (bytes.length > 15 * 1024 * 1024) {
            throw new BusinessException(400, "图片不能超过 15MB");
        }
        String shotId = shot.getId() != null ? shot.getId() : "shot";
        String ext = ".jpg";
        if (originalFilename != null) {
            String lower = originalFilename.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".png")) {
                ext = ".png";
            } else if (lower.endsWith(".webp")) {
                ext = ".webp";
            } else if (lower.endsWith(".jpeg") || lower.endsWith(".jpg")) {
                ext = ".jpg";
            }
        }
        Path visualDir = workDir.resolve("assets").resolve("visual");
        Files.createDirectories(visualDir);
        Path out = visualDir.resolve(shotId + ext);
        Files.write(out, bytes);
        if (shot.getVisual() == null) {
            shot.setVisual(new ShotVisual());
        }
        shot.getVisual().setType("user_image");
        shot.getVisual().setAssetPath("assets/visual/" + shotId + ext);
    }
}
