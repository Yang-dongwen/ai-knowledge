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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Windows 自带 SAPI（System.Speech）→ WAV。
 * 无需 pip；中文依赖系统语音包，质量一般但可本地离线。
 */
@Slf4j
@RequiredArgsConstructor
public class WindowsSapiTtsProvider implements TtsPort {

    private final AigenProperties aigenProperties;
    private final ProcessExecutor processExecutor;
    private final AudioDurationHelper durationHelper;

    @Override
    public TtsResult synthesize(TtsCommand command) throws Exception {
        if (!isWindows()) {
            throw new BusinessException("Windows SAPI 仅支持 Windows 系统");
        }
        String text = command.getText() != null ? command.getText().trim() : "";
        if (text.isEmpty()) {
            throw new BusinessException("TTS 文本为空");
        }
        if (text.length() > 500) {
            text = text.substring(0, 500);
        }

        Path out = command.getOutputFile();
        if (out == null) {
            throw new BusinessException("TTS 输出路径为空");
        }
        // 输出 wav
        Path wav = out.getParent().resolve(command.getSceneId() + ".wav");
        Files.createDirectories(wav.getParent());
        Files.deleteIfExists(wav);

        // Base64 传文本，避免引号转义地狱
        String b64 = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        String wavPath = wav.toAbsolutePath().normalize().toString().replace("'", "''");

        String ps = ""
                + "$ErrorActionPreference='Stop'; "
                + "Add-Type -AssemblyName System.Speech; "
                + "$bytes=[Convert]::FromBase64String('" + b64 + "'); "
                + "$text=[Text.Encoding]::UTF8.GetString($bytes); "
                + "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                + "try { "
                + "  $s.Rate=0; "
                + "  $s.SetOutputToWaveFile('" + wavPath + "'); "
                + "  $s.Speak($text); "
                + "} finally { $s.Dispose() }";

        int timeout = Math.max(30, aigenProperties.getTts().getTimeoutSeconds());
        log.info("Windows SAPI TTS: scene={}, out={}", command.getSceneId(), wav.getFileName());
        processExecutor.execute(List.of(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-Command", ps
        ), timeout);

        if (!Files.isRegularFile(wav) || Files.size(wav) < 128) {
            throw new BusinessException("Windows SAPI 未生成有效 WAV");
        }

        long fallback = StoryboardNormalizeService.estimateNarrationMs(
                text, command.getFallbackDurationFrames(), command.getFps());
        long ms = durationHelper.probeDurationMs(wav, fallback);
        String rel = "assets/audio/" + command.getSceneId() + ".wav";
        return TtsResult.builder()
                .relativeSrc(rel)
                .durationMs(ms)
                .mock(false)
                .build();
    }

    public boolean isAvailable() {
        return isWindows();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
