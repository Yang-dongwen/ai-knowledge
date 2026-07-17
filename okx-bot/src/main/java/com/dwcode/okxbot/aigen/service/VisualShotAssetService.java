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
import com.dwcode.okxbot.aigen.port.ImageToVideoCommand;
import com.dwcode.okxbot.aigen.port.ImageToVideoPort;
import com.dwcode.okxbot.aigen.port.ImageToVideoResult;
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
import java.util.List;
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
            你是电影感文生图提示词导演（适配 FLUX 等）。
            把输入改写成一条「主体扣题 + 镜头语言明确 + 光影质感强」的出图提示词。

            规则：
            1. 与输入同语言（中文进中文出），禁止擅自翻译成另一种语言。
            2. 只输出提示词正文：不要引号、markdown、编号或解释。
            3. 【主题锚点】必须保留输入中的核心专有名词与主体（如以太坊、ETH、区块链、比特币等），
               放在提示词前半段；禁止删掉主体只剩「赛博都市/霓虹/雾气」等空镜。
            4. 补足：景别、机位、主光与氛围、材质；仍保持可拍摄的具体场景。
            5. 不要要求画面内出现大段可读正文/水印；主题图标与界面示意可保留描述。
            6. 约 80～220 字（英文 40～100 词）；不要 NSFW。
            """;

    private final ImageGenPort imageGenPort;
    private final ImgGenProperties imgGenProperties;
    private final AiModelConfigService aiModelConfigService;
    private final AigenProperties aigenProperties;
    private final VisualPlaceholderImageService placeholderImageService;
    private final LlmChatClient llmChatClient;
    private final TtsPort ttsPort;
    private final AigenStorageService storageService;
    private final ImageToVideoPort imageToVideoPort;
    private final TopicRelevanceService topicRelevanceService;

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

        // 用户上传：已有路径且文件在则保留，仍尝试升级为动效片段
        if ("user_image".equals(type) && shot.getVisual().getAssetPath() != null) {
            Path existing = workDir.resolve(shot.getVisual().getAssetPath()).normalize();
            if (Files.isRegularFile(existing) && existing.startsWith(workDir.normalize())) {
                applyImageToVideo(task, workDir, shot, existing, size.width(), size.height(), seedIndex);
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
                // 出图：优先英文 promptEn（FLUX 更稳）+ 主题锚点
                String forImage = resolveImagePrompt(task, shot, finalPrompt);
                generateRealImage(task, workDir, shot, outFile, size.width(), size.height(), forImage, seedIndex);
                shot.getVisual().setType("ai_image");
                // 重生静图时清掉旧动效路径，稍后重新 i2v
                shot.getVisual().setVideoPath(null);
            }
            shot.getVisual().setAssetPath(rel);
            // 静图 → 动感视频片段（感官优先；失败保留静图）
            applyImageToVideo(task, workDir, shot, outFile, size.width(), size.height(), seedIndex);
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
            applyImageToVideo(task, workDir, shot, outFile, size.width(), size.height(), seedIndex);
        }
    }

    /**
     * 对外：用户上传静图后补齐动效短片。
     */
    public void upgradeStillToMotion(AigenTaskEntity task, Path workDir, ShotDto shot,
                                     Path stillFile, int width, int height, int seedIndex) {
        applyImageToVideo(task, workDir, shot, stillFile, width, height, seedIndex);
    }

    /**
     * 将已有静图转为 mp4 动效片段（kinetic / 可选云端 SVD）；成功则 type=ai_video。
     */
    private void applyImageToVideo(AigenTaskEntity task, Path workDir, ShotDto shot, Path stillFile,
                                   int width, int height, int seedIndex) {
        if (shot == null || shot.getVisual() == null || stillFile == null) {
            return;
        }
        // 已有有效动效视频则跳过（允许重复调用时幂等）
        String existingVideo = shot.getVisual().getVideoPath();
        if (existingVideo != null && !existingVideo.isBlank()) {
            Path vp = workDir.resolve(existingVideo).normalize();
            if (Files.isRegularFile(vp)) {
                return;
            }
        }
        try {
            String providerKey = null;
            if (task != null && task.getImageProvider() != null && !task.getImageProvider().isBlank()) {
                providerKey = task.getImageProvider();
            } else if (aigenProperties.getVisual() != null) {
                providerKey = aigenProperties.getVisual().getImageProviderKey();
            }
            ImageToVideoResult r = imageToVideoPort.convert(ImageToVideoCommand.builder()
                    .workDir(workDir)
                    .stillImage(stillFile)
                    .shot(shot)
                    .width(width)
                    .height(height)
                    .seedIndex(seedIndex)
                    .providerKey(providerKey)
                    .build());
            if (r != null && r.isSuccess()) {
                // 页面预览继续用静图 assetPath；mp4 写入 videoPath 供 Remotion 合成
                String stillRel = shot.getVisual().getAssetPath();
                if (stillRel == null || stillRel.isBlank()
                        || stillRel.toLowerCase(Locale.ROOT).endsWith(".mp4")
                        || stillRel.toLowerCase(Locale.ROOT).endsWith(".webm")) {
                    // 若此前误写成视频路径，尽量回指同名静图
                    String stillGuess = guessStillRelative(workDir, stillFile, r.getRelativePath());
                    if (stillGuess != null) {
                        shot.getVisual().setAssetPath(stillGuess);
                    }
                }
                shot.getVisual().setVideoPath(r.getRelativePath());
                shot.getVisual().setType("ai_video");
                log.info("镜头已升级为动效视频: shotId={} provider={} still={} video={}",
                        shot.getId(), r.getProvider(),
                        shot.getVisual().getAssetPath(), r.getRelativePath());
            } else if (r != null && r.getErrorMessage() != null) {
                log.warn("图生视频未成功 shotId={} provider={}: {}",
                        shot.getId(), r.getProvider(), r.getErrorMessage());
            }
        } catch (Exception e) {
            log.warn("动效片段失败，保留静图 shotId={}: {}", shot.getId(), e.getMessage());
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

    /**
     * 选择实际送入文生图模型的提示词。
     * <p>
     * 默认跟随用户语言，不强行英文化：
     * <ul>
     *   <li>auto / follow_user：中文用户 → prompt（中文）；英文用户 → promptEn 或 prompt</li>
     *   <li>en：仅显式配置/用户要求时优先英文 promptEn</li>
     *   <li>zh：强制中文</li>
     * </ul>
     */
    String resolveImagePrompt(AigenTaskEntity task, ShotDto shot, String userLangPromptAfterEnhance) {
        String langMode = aigenProperties.getVisual() != null
                ? aigenProperties.getVisual().getImagePromptLanguage()
                : "auto";
        if (langMode == null || langMode.isBlank()) {
            langMode = "auto";
        }
        langMode = langMode.trim().toLowerCase(Locale.ROOT);
        if ("follow_user".equals(langMode)) {
            langMode = "auto";
        }

        String en = shot != null && shot.getVisual() != null ? shot.getVisual().getPromptEn() : null;
        String userLang = userLangPromptAfterEnhance != null ? userLangPromptAfterEnhance
                : (shot != null && shot.getVisual() != null ? shot.getVisual().getPrompt() : null);
        if (userLang == null) {
            userLang = "";
        }

        boolean userIsEnglish = isEnglishDominant(task, userLang);
        boolean forceEn = "en".equals(langMode);
        boolean forceZh = "zh".equals(langMode);

        String body;
        boolean englishBody;
        if (forceEn) {
            // 用户/配置明确要求英文出图
            if (en != null && !en.isBlank()) {
                body = en.trim();
            } else {
                body = userLang.trim();
            }
            englishBody = true;
        } else if (forceZh) {
            body = userLang.trim();
            englishBody = false;
        } else if (userIsEnglish) {
            // 用户本身用英文：优先英文描述
            if (en != null && !en.isBlank()) {
                body = en.trim();
            } else {
                body = userLang.trim();
            }
            englishBody = true;
        } else {
            // 默认：跟随用户语言（中文提示 → 中文出图）
            body = userLang.trim();
            englishBody = false;
        }

        String userTheme = task != null ? task.getPrompt() : null;
        String prefix = topicRelevanceService != null
                ? topicRelevanceService.buildAnchorPrefix(userTheme, englishBody)
                : "";
        if (prefix.isBlank()) {
            return anchorPromptToTaskTheme(task, body);
        }
        String lower = body.toLowerCase(Locale.ROOT);
        List<String> anchors = topicRelevanceService.extractAnchors(userTheme);
        boolean already = false;
        for (String a : anchors) {
            if (a != null && lower.contains(a.toLowerCase(Locale.ROOT))) {
                already = true;
                break;
            }
        }
        if (already) {
            return body;
        }
        return (prefix + body).trim();
    }

    /**
     * 判断任务/提示是否以英文为主（用于 auto 跟随用户语言）。
     */
    static boolean isEnglishDominant(AigenTaskEntity task, String sampleText) {
        if (task != null && task.getLanguage() != null) {
            String lang = task.getLanguage().trim().toLowerCase(Locale.ROOT);
            if (lang.startsWith("en")) {
                return true;
            }
            if (lang.startsWith("zh") || lang.startsWith("cn") || "chinese".equals(lang)) {
                return false;
            }
        }
        String s = sampleText != null ? sampleText : "";
        if (task != null && task.getPrompt() != null) {
            s = task.getPrompt() + " " + s;
        }
        if (s.isBlank()) {
            return false;
        }
        int cjk = 0;
        int latin = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                cjk++;
            } else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                latin++;
            }
        }
        // 有明显汉字则视为中文用户语境
        if (cjk >= 2) {
            return false;
        }
        return latin >= 8 && latin > cjk * 3;
    }

    /**
     * 将用户任务主题关键词压入出图 prompt 前缀，降低「只有氛围、没有主题」概率。
     */
    static String anchorPromptToTaskTheme(AigenTaskEntity task, String shotPrompt) {
        if (shotPrompt == null) {
            shotPrompt = "";
        }
        String theme = "";
        if (task != null) {
            if (task.getTitle() != null && !task.getTitle().isBlank()) {
                theme = task.getTitle().trim();
            }
            if (task.getPrompt() != null && !task.getPrompt().isBlank()) {
                String p = task.getPrompt().trim().replaceAll("\\s+", " ");
                if (p.length() > 80) {
                    p = p.substring(0, 80);
                }
                theme = theme.isEmpty() ? p : (theme + "，" + p);
            }
        }
        if (theme.isBlank()) {
            return shotPrompt.trim();
        }
        String lowerShot = shotPrompt.toLowerCase(Locale.ROOT);
        String themeHead = theme.length() > 24 ? theme.substring(0, 24) : theme;
        if (lowerShot.contains(themeHead.toLowerCase(Locale.ROOT))) {
            return shotPrompt.trim();
        }
        if (task != null && task.getTitle() != null && !task.getTitle().isBlank()
                && lowerShot.contains(task.getTitle().trim().toLowerCase(Locale.ROOT))) {
            return shotPrompt.trim();
        }
        return ("主题：" + theme + "。画面：" + shotPrompt.trim()).trim();
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
                        ? imgGenProperties.getFlux().getDefaultModel()
                        : "black-forest-labs/flux.1-dev";
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

    /**
     * 根据静图绝对路径或视频相对路径，推断页面预览用的静图相对路径。
     */
    private static String guessStillRelative(Path workDir, Path stillFile, String videoRel) {
        if (stillFile != null && Files.isRegularFile(stillFile) && workDir != null) {
            Path normWork = workDir.toAbsolutePath().normalize();
            Path normStill = stillFile.toAbsolutePath().normalize();
            if (normStill.startsWith(normWork)) {
                return normWork.relativize(normStill).toString().replace('\\', '/');
            }
        }
        if (videoRel == null || videoRel.isBlank()) {
            return null;
        }
        String base = videoRel.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        String dir = slash >= 0 ? base.substring(0, slash + 1) : "";
        String file = slash >= 0 ? base.substring(slash + 1) : base;
        int dot = file.lastIndexOf('.');
        String stem = dot > 0 ? file.substring(0, dot) : file;
        // 去掉 -svd 后缀
        if (stem.endsWith("-svd")) {
            stem = stem.substring(0, stem.length() - 4);
        }
        for (String ext : List.of(".jpg", ".jpeg", ".png", ".webp")) {
            Path p = workDir.resolve(dir + stem + ext).normalize();
            if (Files.isRegularFile(p)) {
                return (dir + stem + ext).replace('\\', '/');
            }
        }
        return null;
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
