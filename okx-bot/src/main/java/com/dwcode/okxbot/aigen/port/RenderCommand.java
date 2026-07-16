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
    /** template 模式分镜 */
    private StoryboardDto storyboard;
    /**
     * visual 模式镜头表（或任意 Remotion inputProps）。
     * 若非 null，优先于 storyboard 作为 inputProps。
     */
    private Object inputProps;
    private Path workDir;
    private String outputFileName;
}
