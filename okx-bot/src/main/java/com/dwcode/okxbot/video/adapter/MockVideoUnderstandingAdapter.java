package com.dwcode.okxbot.video.adapter;

import com.dwcode.okxbot.video.port.VideoUnderstandingCommand;
import com.dwcode.okxbot.video.port.VideoUnderstandingPort;
import com.dwcode.okxbot.video.port.VisualUnderstandingResult;
import com.dwcode.okxbot.video.port.VisualUnderstandingResult.ChunkUnderstanding;
import com.dwcode.okxbot.video.port.VisualUnderstandingResult.OnScreenTextItem;
import com.dwcode.okxbot.video.port.VisualUnderstandingResult.SceneItem;
import com.dwcode.okxbot.video.port.VisualUnderstandingResult.VisualKeyPointItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 本地 Mock 视觉理解，便于无 Omni 额度时联调流水线。
 */
@Slf4j
@Component
public class MockVideoUnderstandingAdapter implements VideoUnderstandingPort {

    @Override
    public VisualUnderstandingResult understand(VideoUnderstandingCommand cmd) {
        log.info("Mock 视觉理解: taskId={}, video={}", cmd.getTaskId(), cmd.getVideoPath());
        VisualUnderstandingResult r = new VisualUnderstandingResult();
        r.setModelId("mock");
        r.setProtocol("mock");
        r.setPartial(false);
        r.setOverallVisualSummary("【Mock】画面理解占位：检测到演示/讲解类画面，含字幕与 UI 元素（未调用真实 Omni）。");
        r.setChunkCount(1);

        SceneItem scene = new SceneItem();
        scene.setStartSec(0.0);
        scene.setEndSec(cmd.getDurationSeconds() != null ? cmd.getDurationSeconds() : 30.0);
        scene.setStartTimestamp("00:00");
        scene.setEndTimestamp("00:30");
        scene.setDescription("Mock 场景：主讲人或产品画面");
        r.getScenes().add(scene);

        OnScreenTextItem ocr = new OnScreenTextItem();
        ocr.setStartSec(5.0);
        ocr.setTimestamp("00:05");
        ocr.setText("Mock OCR 文本");
        r.getOnScreenTexts().add(ocr);

        VisualKeyPointItem kp = new VisualKeyPointItem();
        kp.setStartSec(10.0);
        kp.setTimestamp("00:10");
        kp.setPoint("Mock 视觉要点：画面中出现关键信息卡片");
        kp.setSource("visual");
        r.getVisualKeyPoints().add(kp);

        ChunkUnderstanding ch = new ChunkUnderstanding();
        ch.setIndex(0);
        ch.setChunkStartSec(0);
        ch.setChunkEndSec(cmd.getDurationSeconds() != null ? cmd.getDurationSeconds() : 30);
        ch.setOverallVisualSummary(r.getOverallVisualSummary());
        ch.getScenes().add(scene);
        ch.getOnScreenTexts().add(ocr);
        ch.getVisualKeyPoints().add(kp);
        r.getChunks().add(ch);
        return r;
    }
}
