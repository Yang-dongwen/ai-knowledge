package com.dwcode.okxbot.aigen.agent.step;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.domain.AudioBlockDto;
import com.dwcode.okxbot.aigen.domain.AudioTrackDto;
import com.dwcode.okxbot.aigen.domain.SceneDto;
import com.dwcode.okxbot.aigen.domain.StoryboardDto;
import com.dwcode.okxbot.aigen.domain.shot.ShotDto;
import com.dwcode.okxbot.aigen.domain.shot.ShotlistDto;
import com.dwcode.okxbot.aigen.enums.AigenTaskStatus;
import com.dwcode.okxbot.aigen.port.TtsCommand;
import com.dwcode.okxbot.aigen.port.TtsPort;
import com.dwcode.okxbot.aigen.port.TtsResult;
import com.dwcode.okxbot.aigen.service.AigenStorageService;
import com.dwcode.okxbot.aigen.service.StoryboardNormalizeService;
import com.dwcode.okxbot.aigen.service.VisualPlaceholderImageService;
import com.dwcode.okxbot.imggen.config.ImgGenProperties;
import com.dwcode.okxbot.imggen.port.ImageGenCommand;
import com.dwcode.okxbot.imggen.port.ImageGenPort;
import com.dwcode.okxbot.imggen.port.ImageGenResult;
import com.dwcode.okxbot.imggen.util.AspectRatioMapper;
import com.dwcode.okxbot.video.service.AiModelConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 素材步骤：
 * - template：TTS 配音
 * - visual：每镜主视觉出图 + 可选 BGM 拷贝
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetStep implements PipelineStep {

    private final TtsPort ttsPort;
    private final ImageGenPort imageGenPort;
    private final AigenStorageService storageService;
    private final StoryboardNormalizeService normalizeService;
    private final VisualPlaceholderImageService placeholderImageService;
    private final ObjectMapper objectMapper;
    private final AigenProperties aigenProperties;
    private final ImgGenProperties imgGenProperties;
    private final AiModelConfigService aiModelConfigService;

    @Override
    public String name() {
        return "asset";
    }

    @Override
    public AigenTaskStatus runningStatus() {
        return AigenTaskStatus.ASSET_GENERATING;
    }

    @Override
    public String stepLabel() {
        return "正在生成素材（画面/配音）";
    }

    @Override
    public int progressPercent() {
        return 50;
    }

    @Override
    public void execute(PipelineContext ctx) throws Exception {
        if (ctx.isVisualMode()) {
            executeVisual(ctx);
        } else {
            executeTemplateTts(ctx);
        }
    }

    private void executeVisual(PipelineContext ctx) throws Exception {
        ShotlistDto list = ctx.getShotlist();
        if (list == null && ctx.getTask().getStoryboardJson() != null) {
            list = objectMapper.readValue(ctx.getTask().getStoryboardJson(), ShotlistDto.class);
            ctx.setShotlist(list);
        }
        if (list == null || list.getShots() == null || list.getShots().isEmpty()) {
            throw new IllegalStateException("shotlist 为空，无法生成画面");
        }

        // NVIDIA FLUX 仅允许固定宽高（见 AspectRatioMapper），不能用视频分辨率 1080x1920 直接缩放
        String aspect = list.getMeta() != null && list.getMeta().getAspectRatio() != null
                ? list.getMeta().getAspectRatio()
                : (ctx.getTask().getAspectRatio() != null ? ctx.getTask().getAspectRatio() : "9:16");
        AspectRatioMapper.Size fluxSize = AspectRatioMapper.map(aspect);
        int[] imgSize = new int[]{fluxSize.width(), fluxSize.height()};
        log.info("visual 出图尺寸: aspect={} → {}x{} (FLUX 允许值)", aspect, imgSize[0], imgSize[1]);

        Path visualDir = ctx.getWorkDir().resolve("assets").resolve("visual");
        Files.createDirectories(visualDir);

        boolean mockAsset = aigenProperties.isMockPipeline()
                || "mock".equalsIgnoreCase(aigenProperties.getSteps().getAsset());

        int done = 0;
        int total = list.getShots().size();
        for (ShotDto shot : list.getShots()) {
            if (ctx.getCancelCheck() != null && ctx.getCancelCheck().getAsBoolean()) {
                throw new InterruptedException("cancelled");
            }
            String shotId = shot.getId() != null ? shot.getId() : ("shot-" + (done + 1));
            Path outFile = visualDir.resolve(shotId + ".jpg");
            String rel = "assets/visual/" + shotId + ".jpg";
            String prompt = shot.getVisual() != null ? shot.getVisual().getPrompt() : null;
            String title = shot.getOverlay() != null ? shot.getOverlay().getTitle() : shotId;

            try {
                if (mockAsset || prompt == null || prompt.isBlank()
                        || (shot.getVisual() != null && !"ai_image".equalsIgnoreCase(shot.getVisual().getType()))) {
                    placeholderImageService.writeGradientJpeg(outFile, imgSize[0], imgSize[1],
                            title != null ? title : shotId, done + 1);
                    if (shot.getVisual() != null && (shot.getVisual().getType() == null
                            || "ai_image".equalsIgnoreCase(shot.getVisual().getType()))) {
                        // keep type
                    }
                } else {
                    generateRealImage(ctx, shot, outFile, imgSize[0], imgSize[1], prompt, done);
                }
                if (shot.getVisual() == null) {
                    shot.setVisual(new com.dwcode.okxbot.aigen.domain.shot.ShotVisual());
                }
                shot.getVisual().setAssetPath(rel);
            } catch (Exception e) {
                log.warn("镜头出图失败 shotId={}: {}", shotId, e.getMessage());
                if (aigenProperties.getVisual().isFailOnShotError()) {
                    throw e;
                }
                placeholderImageService.writeGradientJpeg(outFile, imgSize[0], imgSize[1],
                        title != null ? title : shotId, done + 1);
                if (shot.getVisual() == null) {
                    shot.setVisual(new com.dwcode.okxbot.aigen.domain.shot.ShotVisual());
                }
                shot.getVisual().setAssetPath(rel);
                shot.getVisual().setType("gradient");
            }
            done++;
            ctx.getTask().setAssetDoneCount(done);
            ctx.getTask().setCurrentStep("正在生成画面 " + done + "/" + total);
            ctx.getTask().setProgress(30 + (int) (40.0 * done / total));
        }

        // BGM
        if (list.getAudio() != null
                && "bgm_only".equalsIgnoreCase(list.getAudio().getMode())) {
            copyBgmIfPresent(ctx, list);
        }

        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(list);
        Path sbPath = ctx.getWorkDir().resolve("shotlist.json");
        Files.writeString(sbPath, json);
        Files.writeString(ctx.getWorkDir().resolve("storyboard.json"), json);
        ctx.getTask().setStoryboardJson(json);
        ctx.getTask().setStoryboardPath(sbPath.toAbsolutePath().toString());
        ctx.getTask().setShotCount(total);
        ctx.getTask().setAssetDoneCount(done);
        if (list.getMeta() != null && list.getMeta().getDurationInFrames() != null
                && list.getMeta().getFps() != null && list.getMeta().getFps() > 0) {
            ctx.getTask().setDurationSeconds(
                    list.getMeta().getDurationInFrames() / (double) list.getMeta().getFps());
        }
        ctx.setShotlist(list);
    }

    private void generateRealImage(PipelineContext ctx, ShotDto shot, Path outFile,
                                   int width, int height, String prompt, int index) throws Exception {
        Path tmpOut = ctx.getWorkDir().resolve("assets").resolve("visual").resolve("_tmp_" + index);
        Files.createDirectories(tmpOut);

        // 优先任务级选择（前端可选）；否则库表第一个 image 模型；再退回 yml flux
        String provider = ctx.getTask().getImageProvider();
        String modelId = ctx.getTask().getImageModel();
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
            // 回写任务，保证详情展示与下次重试一致
            ctx.getTask().setImageProvider(provider);
            ctx.getTask().setImageModel(modelId);
        } catch (Exception e) {
            log.warn("任务级生图模型解析失败，回退 imggen.flux 配置: {}", e.getMessage());
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
                .taskId(String.valueOf(ctx.getTaskId()))
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
                .workDir(ctx.getWorkDir())
                .outputsDir(tmpOut)
                .build();
        ImageGenResult result = imageGenPort.generate(cmd);
        if (result.getImages() == null || result.getImages().isEmpty()) {
            throw new IllegalStateException("出图结果为空");
        }
        Path generated = tmpOut.resolve(Path.of(result.getImages().get(0).getRelativePath()).getFileName().toString());
        // ImageAsset relativePath is like outputs/img-01.jpg relative to workDir of imggen - but we set outputsDir=tmpOut
        // relativePath is "outputs/img-01.jpg" from flux adapter - actually "outputs/" + name relative to workDir
        // Looking at NvidiaFluxImageAdapter - relativePath is "outputs/" + name and file is outDir.resolve(name)
        // outDir is outputsDir = tmpOut, so file is tmpOut/img-01.jpg, relativePath is outputs/img-01.jpg - inconsistent!
        // Actually: Path file = outDir.resolve(name) where outDir = cmd.getOutputsDir() = tmpOut
        // relativePath = "outputs/" + name - so wrong path. Looking again:
        // assets.add(... relativePath("outputs/" + name) ...)
        // file is outDir.resolve(name) = tmpOut/img-01.jpg
        // So we should list tmpOut for jpg/png
        Path found = null;
        try (var stream = Files.list(tmpOut)) {
            found = stream.filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png");
            }).findFirst().orElse(null);
        }
        if (found == null || !Files.isRegularFile(found)) {
            // try relative from workDir
            Path alt = ctx.getWorkDir().resolve(result.getImages().get(0).getRelativePath());
            if (Files.isRegularFile(alt)) {
                found = alt;
            }
        }
        if (found == null) {
            throw new IllegalStateException("找不到生成的图片文件");
        }
        Files.copy(found, outFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private void copyBgmIfPresent(PipelineContext ctx, ShotlistDto list) {
        try {
            Path bgmDir = Path.of(aigenProperties.getVisual().getBgmDir()).toAbsolutePath().normalize();
            if (!Files.isDirectory(bgmDir)) {
                log.info("BGM 目录不存在，跳过: {}", bgmDir);
                return;
            }
            String bgmId = list.getAudio().getBgmId();
            if (bgmId == null || bgmId.isBlank()) {
                bgmId = ctx.getTask().getBgmId();
            }
            Path src = null;
            if (bgmId != null && !bgmId.isBlank()) {
                Path named = bgmDir.resolve(bgmId);
                if (Files.isRegularFile(named)) {
                    src = named;
                } else if (Files.isRegularFile(bgmDir.resolve(bgmId + ".mp3"))) {
                    src = bgmDir.resolve(bgmId + ".mp3");
                }
            }
            if (src == null) {
                try (var stream = Files.list(bgmDir)) {
                    src = stream.filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".m4a");
                    }).findFirst().orElse(null);
                }
            }
            if (src == null) {
                log.info("未找到预置 BGM 文件，纯画面出片");
                return;
            }
            Path audioDir = ctx.getWorkDir().resolve("assets").resolve("audio");
            Files.createDirectories(audioDir);
            String ext = src.getFileName().toString();
            int dot = ext.lastIndexOf('.');
            String suffix = dot > 0 ? ext.substring(dot) : ".mp3";
            Path dest = audioDir.resolve("bgm" + suffix);
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
            list.getAudio().setBgmSrc("assets/audio/bgm" + suffix);
            list.getAudio().setBgmId(src.getFileName().toString());
            ctx.getTask().setBgmId(list.getAudio().getBgmId());
            log.info("已拷贝 BGM: {} -> {}", src, dest);
        } catch (Exception e) {
            log.warn("拷贝 BGM 失败: {}", e.getMessage());
        }
    }

    private void executeTemplateTts(PipelineContext ctx) throws Exception {
        StoryboardDto sb = ctx.getStoryboard();
        if (sb == null) {
            throw new IllegalStateException("storyboard 为空，无法生成素材");
        }
        int fps = sb.getMeta() != null && sb.getMeta().getFps() != null ? sb.getMeta().getFps() : 30;
        String voice = ctx.getTask().getVoiceId();
        if (voice == null || voice.isBlank()) {
            voice = aigenProperties.getTts().getDefaultVoice();
        }

        if (sb.getAudio() == null) {
            sb.setAudio(new AudioBlockDto());
        }
        sb.getAudio().setVoiceId(voice);
        List<AudioTrackDto> tracks = new ArrayList<>();

        int i = 0;
        int total = sb.getScenes().size();
        for (SceneDto scene : sb.getScenes()) {
            if (ctx.getCancelCheck() != null && ctx.getCancelCheck().getAsBoolean()) {
                throw new InterruptedException("cancelled");
            }
            i++;
            String narration = scene.getNarration() != null ? scene.getNarration().trim() : "";
            if (narration.isEmpty()) {
                narration = scene.getProps() != null && scene.getProps().getTitle() != null
                        ? scene.getProps().getTitle()
                        : " ";
            }

            String relGuess = "assets/audio/" + scene.getId() + ".mp3";
            Path outGuess = storageService.resolveAsset(ctx.getWorkDir(), relGuess);

            log.info("TTS 场景 {}/{}: sceneId={}, chars={}", i, total, scene.getId(), narration.length());
            TtsResult result = ttsPort.synthesize(TtsCommand.builder()
                    .sceneId(scene.getId())
                    .text(narration)
                    .voiceId(voice)
                    .language(ctx.getTask().getLanguage())
                    .outputFile(outGuess)
                    .fallbackDurationFrames(scene.getDurationInFrames())
                    .fps(fps)
                    .build());

            String rel = result.getRelativeSrc() != null
                    ? result.getRelativeSrc()
                    : relGuess;
            storageService.resolveAsset(ctx.getWorkDir(), rel);

            AudioTrackDto tr = new AudioTrackDto();
            tr.setSceneId(scene.getId());
            tr.setSrc(rel);
            tr.setDurationMs(result.getDurationMs());
            tr.setMock(result.isMock());
            tracks.add(tr);

            if (i < total) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
        }
        sb.getAudio().setTracks(tracks);

        normalizeService.realignByAudioTracks(sb, aigenProperties.getTts().getTailPaddingFrames());

        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(sb);
        Path sbPath = ctx.getWorkDir().resolve("storyboard.json");
        Files.writeString(sbPath, json);
        ctx.getTask().setStoryboardJson(json);
        ctx.getTask().setStoryboardPath(sbPath.toAbsolutePath().toString());
        if (sb.getMeta() != null && sb.getMeta().getDurationInFrames() != null && fps > 0) {
            ctx.getTask().setDurationSeconds(sb.getMeta().getDurationInFrames() / (double) fps);
        }
        ctx.setStoryboard(sb);
    }
}
