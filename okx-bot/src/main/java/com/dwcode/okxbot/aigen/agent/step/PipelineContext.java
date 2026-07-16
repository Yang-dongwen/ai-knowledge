package com.dwcode.okxbot.aigen.agent.step;

import com.dwcode.okxbot.aigen.domain.StoryboardDto;
import com.dwcode.okxbot.aigen.domain.shot.ShotlistDto;
import com.dwcode.okxbot.aigen.entity.AigenTaskEntity;
import lombok.Data;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

@Data
public class PipelineContext {
    private Long taskId;
    private AigenTaskEntity task;
    private Path workDir;
    private StoryboardDto storyboard;
    /** visual 模式镜头表 */
    private ShotlistDto shotlist;
    private BooleanSupplier cancelCheck;
    private long pipelineStartMs;

    public boolean isVisualMode() {
        if (task == null) {
            return false;
        }
        String m = task.getPipelineMode();
        return m != null && "visual".equalsIgnoreCase(m.trim());
    }
}
