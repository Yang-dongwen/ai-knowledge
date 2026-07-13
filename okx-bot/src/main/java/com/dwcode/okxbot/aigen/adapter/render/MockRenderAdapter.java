package com.dwcode.okxbot.aigen.adapter.render;

import com.dwcode.okxbot.aigen.port.RenderCommand;
import com.dwcode.okxbot.aigen.port.RenderResult;
import com.dwcode.okxbot.aigen.port.VideoRenderPort;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * mock 渲染：不生成 MP4，写说明文件。
 */
@Slf4j
public class MockRenderAdapter implements VideoRenderPort {

    @Override
    public RenderResult render(RenderCommand command) throws Exception {
        long t0 = System.currentTimeMillis();
        Path note = command.getWorkDir().resolve("MOCK_OUTPUT.txt");
        Files.writeString(note,
                "Mock render only. Set aigen.steps.render=real and start aigen-remotion.\n"
                        + "jobId=" + command.getJobId() + "\n",
                StandardCharsets.UTF_8);
        return RenderResult.builder()
                .success(true)
                .outputAbsolutePath(null)
                .renderDurationMs(System.currentTimeMillis() - t0)
                .build();
    }
}
