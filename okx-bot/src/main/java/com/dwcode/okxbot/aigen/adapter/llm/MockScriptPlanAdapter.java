package com.dwcode.okxbot.aigen.adapter.llm;

import com.dwcode.okxbot.aigen.domain.*;
import com.dwcode.okxbot.aigen.port.PlanCommand;
import com.dwcode.okxbot.aigen.port.ScriptPlanPort;
import com.dwcode.okxbot.aigen.service.StoryboardNormalizeService;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 无 LLM 时的分镜生成（mock / 降级）。由 {@link com.dwcode.okxbot.aigen.config.AigenBeanConfig} 装配。
 */
@RequiredArgsConstructor
public class MockScriptPlanAdapter implements ScriptPlanPort {

    private final StoryboardNormalizeService normalizeService;

    @Override
    public StoryboardDto plan(PlanCommand command) {
        StoryboardDto sb = new StoryboardDto();
        sb.setVersion("1.0");
        StoryboardMeta meta = new StoryboardMeta();
        meta.setTitle(truncate(command.getTitleHint() != null ? command.getTitleHint() : command.getPrompt(), 40));
        meta.setTemplateId(command.getTemplateId());
        meta.setLanguage(command.getLanguage());
        sb.setMeta(meta);

        String prompt = command.getPrompt() != null ? command.getPrompt().trim() : "";
        List<SceneDto> scenes = new ArrayList<>();

        SceneDto s1 = new SceneDto();
        s1.setId("s1");
        s1.setType("title");
        s1.setNarration(truncate(prompt, 80));
        SceneProps p1 = new SceneProps();
        p1.setTitle(meta.getTitle());
        p1.setSubtitle("AI 视频生成");
        s1.setProps(p1);
        scenes.add(s1);

        SceneDto s2 = new SceneDto();
        s2.setId("s2");
        s2.setType("bullets");
        s2.setNarration("以下是本期要点：" + truncate(prompt, 100));
        SceneProps p2 = new SceneProps();
        p2.setHeading("核心要点");
        List<String> items = new ArrayList<>();
        items.add(truncate(prompt, 36));
        items.add("结构化分镜由系统自动规划");
        items.add("成片由 Remotion 渲染输出");
        p2.setItems(items);
        s2.setProps(p2);
        scenes.add(s2);

        SceneDto s3 = new SceneDto();
        s3.setId("s3");
        s3.setType("outro");
        s3.setNarration("感谢观看，我们下期再见。");
        SceneProps p3 = new SceneProps();
        p3.setTitle("感谢观看");
        p3.setCta("点赞收藏");
        s3.setProps(p3);
        scenes.add(s3);

        sb.setScenes(scenes);
        return normalizeService.normalize(
                sb,
                command.getTemplateId(),
                command.getAspectRatio(),
                command.getTargetDurationSec(),
                command.getLanguage(),
                meta.getTitle()
        );
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
