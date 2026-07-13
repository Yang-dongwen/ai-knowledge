package com.dwcode.okxbot.aigen.port;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;

@Data
@Builder
public class TtsCommand {
    private String sceneId;
    private String text;
    private String voiceId;
    private String language;
    /** 输出文件绝对路径（已通过安全校验） */
    private Path outputFile;
    private int fallbackDurationFrames;
    private int fps;
}
