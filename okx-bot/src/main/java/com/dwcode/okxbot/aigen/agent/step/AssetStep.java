package com.dwcode.okxbot.aigen.agent.step;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.domain.AudioBlockDto;
import com.dwcode.okxbot.aigen.domain.AudioTrackDto;
import com.dwcode.okxbot.aigen.domain.SceneDto;
import com.dwcode.okxbot.aigen.domain.StoryboardDto;
import com.dwcode.okxbot.aigen.domain.shot.ShotDto;
import com.dwcode.okxbot.aigen.domain.shot.ShotlistDto;
import com.dwcode.okxbot.aigen.entity.AigenTaskEntity;
import com.dwcode.okxbot.aigen.enums.AigenTaskStatus;
import com.dwcode.okxbot.aigen.event.AigenTaskEventPublisher;
import com.dwcode.okxbot.aigen.mapper.AigenTaskMapper;
import com.dwcode.okxbot.aigen.port.TtsCommand;
import com.dwcode.okxbot.aigen.port.TtsPort;
import com.dwcode.okxbot.aigen.port.TtsResult;
import com.dwcode.okxbot.aigen.service.AigenStorageService;
import com.dwcode.okxbot.aigen.service.StoryboardNormalizeService;
import com.dwcode.okxbot.aigen.service.VisualShotAssetService;
import com.dwcode.okxbot.imggen.util.AspectRatioMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 素材步骤：
 * - template：TTS 配音
 * - visual：每镜主视觉（可并行）+ 可选 BGM / TTS
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetStep implements PipelineStep {

    private final TtsPort ttsPort;
    private final AigenStorageService storageService;
    private final StoryboardNormalizeService normalizeService;
    private final VisualShotAssetService visualShotAssetService;
    private final ObjectMapper objectMapper;
    private final AigenProperties aigenProperties;
    private final AigenTaskMapper aigenTaskMapper;
    private final AigenTaskEventPublisher eventPublisher;

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

        AspectRatioMapper.Size fluxSize = visualShotAssetService.resolveFluxSize(ctx.getTask(), list);
        int total = list.getShots().size();
        int concurrency = Math.max(1, aigenProperties.getVisual().getImageConcurrency());
        log.info("visual 出图尺寸: {}x{}, shots={}, concurrency={}",
                fluxSize.width(), fluxSize.height(), total, concurrency);

        boolean mockAsset = aigenProperties.isMockPipeline()
                || "mock".equalsIgnoreCase(aigenProperties.getSteps().getAsset());
        boolean enhance = ctx.getTask().getEnhanceImagePrompt() != null
                && ctx.getTask().getEnhanceImagePrompt() == 1;

        publishProgress(ctx, "正在生成画面 0/" + total, 30, 0);

        // —— 并行出图（有界线程池）——
        AtomicInteger done = new AtomicInteger(0);
        AtomicReference<Exception> firstError = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(concurrency, total),
                r -> {
                    Thread t = new Thread(r, "aigen-visual-img");
                    t.setDaemon(true);
                    return t;
                });
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            List<ShotDto> shots = list.getShots();
            for (int i = 0; i < shots.size(); i++) {
                final int index = i;
                final ShotDto shot = shots.get(i);
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        if (ctx.getCancelCheck() != null && ctx.getCancelCheck().getAsBoolean()) {
                            throw new InterruptedException("cancelled");
                        }
                        if (firstError.get() != null) {
                            return;
                        }
                        visualShotAssetService.materializeShotImage(
                                ctx.getTask(), ctx.getWorkDir(), shot, fluxSize,
                                index + 1, mockAsset, enhance);
                        int d = done.incrementAndGet();
                        int progress = 30 + (int) (35.0 * d / total);
                        publishProgress(ctx, "正在生成画面 " + d + "/" + total, progress, d);
                        log.info("画面完成: taskId={} shot={}/{} id={}",
                                ctx.getTaskId(), d, total, shot.getId());
                    } catch (Exception e) {
                        firstError.compareAndSet(null, e);
                        throw new RuntimeException(e);
                    }
                }, pool));
            }
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } catch (Exception joinEx) {
                // 展开 completionException
            }
            Exception err = firstError.get();
            if (err != null) {
                if (err instanceof InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
                if (err.getMessage() != null
                        && (err.getMessage().contains("cancelled") || err.getMessage().contains("paused"))) {
                    throw new InterruptedException(err.getMessage());
                }
                throw err;
            }
        } finally {
            pool.shutdownNow();
        }

        int imageDone = done.get();
        String audioMode = list.getAudio() != null && list.getAudio().getMode() != null
                ? list.getAudio().getMode().toLowerCase(Locale.ROOT)
                : (ctx.getTask().getAudioMode() != null ? ctx.getTask().getAudioMode().toLowerCase(Locale.ROOT) : "none");

        // BGM
        if ("bgm_only".equals(audioMode) || "tts_bgm".equals(audioMode)) {
            copyBgmIfPresent(ctx, list);
        }

        // Visual TTS（口播也并行，并发取 min(2, concurrency)）
        if ("tts".equals(audioMode) || "tts_bgm".equals(audioMode)) {
            int fps = list.getMeta() != null && list.getMeta().getFps() != null
                    ? list.getMeta().getFps() : 30;
            int ttsConcurrency = Math.min(2, Math.max(1, concurrency));
            publishProgress(ctx, "正在生成口播 0/" + total, 66, imageDone);
            runParallelTts(ctx, list, fps, total, ttsConcurrency);
            visualShotAssetService.realignShotlistTimeline(list);
        }

        persistShotlist(ctx, list, total, imageDone);
        publishProgress(ctx, "素材已就绪（画面 " + imageDone + "/" + total + "）", 70, imageDone);
    }

    private void runParallelTts(PipelineContext ctx, ShotlistDto list, int fps,
                                int total, int concurrency) throws Exception {
        AtomicInteger done = new AtomicInteger(0);
        AtomicReference<Exception> firstError = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(concurrency, total),
                r -> {
                    Thread t = new Thread(r, "aigen-visual-tts");
                    t.setDaemon(true);
                    return t;
                });
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (ShotDto shot : list.getShots()) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        if (ctx.getCancelCheck() != null && ctx.getCancelCheck().getAsBoolean()) {
                            throw new InterruptedException("cancelled");
                        }
                        if (firstError.get() != null) {
                            return;
                        }
                        visualShotAssetService.synthesizeShotNarration(
                                ctx.getTask(), ctx.getWorkDir(), shot, fps);
                        int d = done.incrementAndGet();
                        int progress = 66 + (int) (4.0 * d / total);
                        publishProgress(ctx, "正在生成口播 " + d + "/" + total, progress, null);
                    } catch (Exception e) {
                        firstError.compareAndSet(null, e);
                        throw new RuntimeException(e);
                    }
                }, pool));
            }
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } catch (Exception ignored) {
                // firstError
            }
            Exception err = firstError.get();
            if (err != null) {
                if (err instanceof InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
                throw err;
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 每镜进度：写库 + SSE，让前端能看到「画面 n/m」。
     */
    private void publishProgress(PipelineContext ctx, String step, int progress, Integer assetDone) {
        AigenTaskEntity task = ctx.getTask();
        if (task == null || task.getId() == null) {
            return;
        }
        synchronized (task) {
            try {
                task.setCurrentStep(step);
                task.setProgress(Math.min(99, Math.max(0, progress)));
                if (assetDone != null) {
                    task.setAssetDoneCount(assetDone);
                }
                task.setUpdatedAt(LocalDateTime.now());
                // 只更新进度相关列，避免并行线程互相覆盖大字段
                AigenTaskEntity patch = new AigenTaskEntity();
                patch.setId(task.getId());
                patch.setCurrentStep(task.getCurrentStep());
                patch.setProgress(task.getProgress());
                patch.setAssetDoneCount(task.getAssetDoneCount());
                patch.setUpdatedAt(task.getUpdatedAt());
                if (task.getImageProvider() != null) {
                    patch.setImageProvider(task.getImageProvider());
                }
                if (task.getImageModel() != null) {
                    patch.setImageModel(task.getImageModel());
                }
                aigenTaskMapper.updateById(patch);
                eventPublisher.publishEntity(task, AigenTaskEventPublisher.TYPE_STATUS);
            } catch (Exception e) {
                log.debug("素材进度推送失败: {}", e.getMessage());
            }
        }
    }

    private void persistShotlist(PipelineContext ctx, ShotlistDto list, int total, int done) throws Exception {
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

    private void copyBgmIfPresent(PipelineContext ctx, ShotlistDto list) {
        try {
            Path bgmDir = Path.of(aigenProperties.getVisual().getBgmDir()).toAbsolutePath().normalize();
            if (!Files.isDirectory(bgmDir)) {
                log.info("BGM 目录不存在，跳过: {}", bgmDir);
                return;
            }
            if (list.getAudio() == null) {
                list.setAudio(new com.dwcode.okxbot.aigen.domain.shot.ShotlistAudio());
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
                log.info("未找到预置 BGM 文件");
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
