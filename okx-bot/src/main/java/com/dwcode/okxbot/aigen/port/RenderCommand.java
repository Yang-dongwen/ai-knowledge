package com.dwcode.okxbot.aigen.port;

import com.dwcode.okxbot.aigen.domain.StoryboardDto;
import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;

@Data
@Builder
public class RenderCommand {
    private String jobId;
    private String compositionId;
    private StoryboardDto storyboard;
    private Path workDir;
    private String outputFileName;
}
