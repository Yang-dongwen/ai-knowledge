package com.dwcode.okxbot.aigen.adapter.tts;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.port.TtsCommand;
import com.dwcode.okxbot.aigen.port.TtsPort;
import com.dwcode.okxbot.aigen.port.TtsResult;
import com.dwcode.okxbot.aigen.service.StoryboardNormalizeService;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.util.ProcessExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Edge TTS（Microsoft 在线神经语音，免费 CLI）。
 * 依赖：pip install edge-tts，命令 edge-tts 或 python -m edge_tts。
 */
@Slf4j
@RequiredArgsConstructor
public class EdgeTtsProvider implements TtsPort {

    private final AigenProperties aigenProperties;
    private final ProcessExecutor processExecutor;
    private final AudioDurationHelper durationHelper;

    @Override
    public TtsResult synthesize(TtsCommand command) throws Exception {
        String text = command.getText() != null ? command.getText().trim() : "";
        if (text.isEmpty()) {
            throw new BusinessException("TTS 文本为空: scene=" + command.getSceneId());
        }
        if (text.length() > 500) {
            text = text.substring(0, 500);
        }

        Path out = command.getOutputFile();
        if (out == null) {
            throw new BusinessException("TTS 输出路径为空");
        }
        // 统一 mp3
        if (!out.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp3")) {
            out = out.getParent().resolve(command.getSceneId() + ".mp3");
        }
        Files.createDirectories(out.getParent());
        Files.deleteIfExists(out);

        String voice = command.getVoiceId() != null && !command.getVoiceId().isBlank()
                ? command.getVoiceId()
                : aigenProperties.getTts().getDefaultVoice();
        int timeout = Math.max(30, aigenProperties.getTts().getTimeoutSeconds());

        List<String> cmd = buildCommand(voice, text, out);
        log.info("Edge-TTS: scene={}, voice={}, out={}", command.getSceneId(), voice, out.getFileName());
        processExecutor.execute(cmd, timeout);

        if (!Files.isRegularFile(out) || Files.size(out) < 64) {
            throw new BusinessException("Edge-TTS 未生成有效音频文件");
        }

        long fallback = StoryboardNormalizeService.estimateNarrationMs(
                text, command.getFallbackDurationFrames(), command.getFps());
        long ms = durationHelper.probeDurationMs(out, fallback);
        String rel = "assets/audio/" + command.getSceneId() + ".mp3";
        return TtsResult.builder()
                .relativeSrc(rel)
                .durationMs(ms)
                .mock(false)
                .build();
    }

    private List<String> buildCommand(String voice, String text, Path out) {
        AigenProperties.Tts tts = aigenProperties.getTts();
        String mode = tts.getEdgeMode() != null ? tts.getEdgeMode().trim().toLowerCase(Locale.ROOT) : "auto";
        Path abs = out.toAbsolutePath().normalize();

        if ("python".equals(mode) || ("auto".equals(mode) && preferPython(tts))) {
            return pythonModuleCmd(tts, voice, text, abs);
        }
        if ("cli".equals(mode) || "auto".equals(mode)) {
            List<String> cli = new ArrayList<>();
            cli.add(blank(tts.getEdgeCommand()) ? "edge-tts" : tts.getEdgeCommand().trim());
            cli.add("--voice");
            cli.add(voice);
            cli.add("--text");
            cli.add(text);
            cli.add("--write-media");
            cli.add(abs.toString());
            return cli;
        }
        return pythonModuleCmd(tts, voice, text, abs);
    }

    private static List<String> pythonModuleCmd(AigenProperties.Tts tts, String voice, String text, Path abs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(blank(tts.getPythonPath()) ? "python" : tts.getPythonPath().trim());
        cmd.add("-m");
        cmd.add("edge_tts");
        cmd.add("--voice");
        cmd.add(voice);
        cmd.add("--text");
        cmd.add(text);
        cmd.add("--write-media");
        cmd.add(abs.toString());
        return cmd;
    }

    private static boolean preferPython(AigenProperties.Tts tts) {
        // 显式配置了 python-path 时优先模块方式
        return tts.getPythonPath() != null && !tts.getPythonPath().isBlank();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** 探测 edge-tts 是否可用（轻量） */
    public boolean isAvailable() {
        try {
            AigenProperties.Tts tts = aigenProperties.getTts();
            List<String> cmd = new ArrayList<>();
            if (preferPython(tts) || "python".equalsIgnoreCase(tts.getEdgeMode())) {
                cmd.add(blank(tts.getPythonPath()) ? "python" : tts.getPythonPath().trim());
                cmd.add("-m");
                cmd.add("edge_tts");
                cmd.add("--version");
            } else {
                cmd.add(blank(tts.getEdgeCommand()) ? "edge-tts" : tts.getEdgeCommand().trim());
                cmd.add("--version");
            }
            processExecutor.execute(cmd, 15);
            return true;
        } catch (Exception e) {
            log.debug("Edge-TTS 不可用: {}", e.getMessage());
            return false;
        }
    }
}
