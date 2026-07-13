package com.dwcode.okxbot.aigen.agent.step;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.domain.AudioBlockDto;
import com.dwcode.okxbot.aigen.domain.AudioTrackDto;
import com.dwcode.okxbot.aigen.domain.SceneDto;
import com.dwcode.okxbot.aigen.domain.StoryboardDto;
import com.dwcode.okxbot.aigen.enums.AigenTaskStatus;
import com.dwcode.okxbot.aigen.port.TtsCommand;
import com.dwcode.okxbot.aigen.port.TtsPort;
import com.dwcode.okxbot.aigen.port.TtsResult;
import com.dwcode.okxbot.aigen.service.AigenStorageService;
import com.dwcode.okxbot.aigen.service.StoryboardNormalizeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 素材步骤：TTS 生成每场口播音频，并按真实时长重排分镜时间轴。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetStep implements PipelineStep {

    private final TtsPort ttsPort;
    private final AigenStorageService storageService;
    private final StoryboardNormalizeService normalizeService;
    private final ObjectMapper objectMapper;
    private final AigenProperties aigenProperties;

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
        return "正在生成配音与字幕时间轴";
    }

    @Override
    public int progressPercent() {
        return 50;
    }

    @Override
    public void execute(PipelineContext ctx) throws Exception {
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

            // 输出占位路径：真实 TTS 会改成 .mp3/.wav；mock 用 .mock.txt
            String relGuess = "assets/audio/" + scene.getId() + ".mp3";
            Path outGuess = storageService.resolveAsset(ctx.getWorkDir(), relGuess);

            log.info("TTS 场景 {}/{}: sceneId={}", i, total, scene.getId());
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
            // 校验相对路径安全
            storageService.resolveAsset(ctx.getWorkDir(), rel);

            AudioTrackDto tr = new AudioTrackDto();
            tr.setSceneId(scene.getId());
            tr.setSrc(rel);
            tr.setDurationMs(result.getDurationMs());
            tr.setMock(result.isMock());
            tracks.add(tr);
        }
        sb.getAudio().setTracks(tracks);

        // 按真实音频时长重排时间轴
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
