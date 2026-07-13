package com.dwcode.okxbot.aigen;

import com.dwcode.okxbot.aigen.domain.*;
import com.dwcode.okxbot.aigen.service.StoryboardValidateService;
import com.dwcode.okxbot.aigen.service.TemplateRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InsightCompareValidateTest {

    StoryboardValidateService validate;

    @BeforeEach
    void setUp() {
        validate = new StoryboardValidateService(new TemplateRegistry());
    }

    @Test
    void acceptsGoldenInsightCompare() {
        StoryboardDto sb = golden();
        List<String> errors = validate.validate(sb, TemplateRegistry.INSIGHT_COMPARE, "9:16", 30);
        assertTrue(errors.isEmpty(), () -> String.join("; ", errors));
    }

    @Test
    void rejectsCompareWithoutSides() {
        StoryboardDto sb = golden();
        SceneDto compare = sb.getScenes().stream()
                .filter(s -> "compare".equals(s.getType()))
                .findFirst()
                .orElseThrow();
        compare.getProps().setLeftItems(List.of("only-one"));
        List<String> errors = validate.validate(sb, TemplateRegistry.INSIGHT_COMPARE, "9:16", 30);
        assertTrue(errors.stream().anyMatch(e -> e.contains("leftItems") || e.contains("rightItems")));
    }

    @Test
    void rejectsMissingCompare() {
        StoryboardDto sb = golden();
        sb.getScenes().removeIf(s -> "compare".equals(s.getType()));
        List<String> errors = validate.validate(sb, TemplateRegistry.INSIGHT_COMPARE, "9:16", 30);
        assertTrue(errors.stream().anyMatch(e -> e.contains("compare")));
    }

    private static StoryboardDto golden() {
        StoryboardDto sb = new StoryboardDto();
        sb.setVersion("1.0");
        StoryboardMeta meta = new StoryboardMeta();
        meta.setTemplateId(TemplateRegistry.INSIGHT_COMPARE);
        meta.setFps(30);
        meta.setWidth(1080);
        meta.setHeight(1920);
        meta.setDurationInFrames(720);
        sb.setMeta(meta);

        List<SceneDto> scenes = new ArrayList<>();
        scenes.add(scene("s1", "hook", hookProps()));
        scenes.add(scene("s2", "compare", compareProps()));
        scenes.add(scene("s3", "metric", metricProps()));
        scenes.add(scene("s4", "outro", outroProps()));
        sb.setScenes(scenes);
        return sb;
    }

    private static SceneDto scene(String id, String type, SceneProps props) {
        SceneDto s = new SceneDto();
        s.setId(id);
        s.setType(type);
        s.setNarration("口播内容示例");
        s.setStartFrame(0);
        s.setDurationInFrames(90);
        s.setProps(props);
        return s;
    }

    private static SceneProps hookProps() {
        SceneProps p = new SceneProps();
        p.setEyebrow("误区");
        p.setTitle("先场景还是先模型？");
        p.setSubtitle("路径决定结果");
        return p;
    }

    private static SceneProps compareProps() {
        SceneProps p = new SceneProps();
        p.setHeading("对比");
        p.setLeftLabel("无效");
        p.setRightLabel("有效");
        p.setLeftItems(List.of("堆概念", "无验收"));
        p.setRightItems(List.of("锁场景", "最小闭环"));
        return p;
    }

    private static SceneProps metricProps() {
        SceneProps p = new SceneProps();
        p.setValue("14");
        p.setUnit("天");
        p.setLabel("可演示");
        return p;
    }

    private static SceneProps outroProps() {
        SceneProps p = new SceneProps();
        p.setTitle("先场景后模型");
        p.setCta("关注");
        return p;
    }
}
