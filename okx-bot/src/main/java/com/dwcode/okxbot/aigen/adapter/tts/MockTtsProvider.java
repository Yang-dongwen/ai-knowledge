package com.dwcode.okxbot.aigen.adapter.tts;

import com.dwcode.okxbot.aigen.port.TtsCommand;
import com.dwcode.okxbot.aigen.port.TtsPort;
import com.dwcode.okxbot.aigen.port.TtsResult;
import com.dwcode.okxbot.aigen.service.StoryboardNormalizeService;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Phase 1：语音后补 — 仅估算时长并写占位标记文件，不生成真实音频。
 */
@Slf4j
public class MockTtsProvider implements TtsPort {

    @Override
    public TtsResult synthesize(TtsCommand command) throws Exception {
        long ms = StoryboardNormalizeService.estimateNarrationMs(
                command.getText(),
                command.getFallbackDurationFrames(),
                command.getFps() > 0 ? command.getFps() : 30
        );
        String rel = "assets/audio/" + command.getSceneId() + ".mock.txt";
        if (command.getOutputFile() != null) {
            Path out = command.getOutputFile().getParent().resolve(command.getSceneId() + ".mock.txt");
            Files.createDirectories(out.getParent());
            String note = "MOCK_TTS\nscene=" + command.getSceneId()
                    + "\ndurationMs=" + ms
                    + "\ntext=" + (command.getText() != null ? command.getText() : "")
                    + "\n";
            Files.writeString(out, note, StandardCharsets.UTF_8);
        }
        log.debug("Mock TTS: scene={}, durationMs={}", command.getSceneId(), ms);
        return TtsResult.builder()
                .relativeSrc(rel)
                .durationMs(ms)
                .mock(true)
                .build();
    }
}
