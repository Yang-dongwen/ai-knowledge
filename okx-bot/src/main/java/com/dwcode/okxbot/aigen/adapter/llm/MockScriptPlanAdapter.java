package com.dwcode.okxbot.aigen.adapter.llm;

import com.dwcode.okxbot.aigen.domain.*;
import com.dwcode.okxbot.aigen.port.PlanCommand;
import com.dwcode.okxbot.aigen.port.ScriptPlanPort;
import com.dwcode.okxbot.aigen.service.StoryboardNormalizeService;
import com.dwcode.okxbot.aigen.service.TemplateRegistry;
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
        List<SceneDto> scenes = TemplateRegistry.INSIGHT_COMPARE.equals(command.getTemplateId())
                ? insightScenes(meta.getTitle(), prompt)
                : knowledgeScenes(meta.getTitle(), prompt);

        sb.setScenes(scenes);
        StoryboardStyle style = new StoryboardStyle();
        if (TemplateRegistry.INSIGHT_COMPARE.equals(command.getTemplateId())) {
            style.setTheme("compare-duo");
            style.setPrimaryColor("#0ea5e9");
        } else {
            style.setTheme("tech-dark");
            style.setPrimaryColor("#6366F1");
        }
        sb.setStyle(style);

        return normalizeService.normalize(
                sb,
                command.getTemplateId(),
                command.getAspectRatio(),
                command.getTargetDurationSec(),
                command.getLanguage(),
                meta.getTitle()
        );
    }

    private static List<SceneDto> knowledgeScenes(String title, String prompt) {
        List<SceneDto> scenes = new ArrayList<>();

        SceneDto s1 = new SceneDto();
        s1.setId("s1");
        s1.setType("title");
        s1.setNarration(truncate(prompt, 80));
        SceneProps p1 = new SceneProps();
        p1.setTitle(title);
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
        return scenes;
    }

    private static List<SceneDto> insightScenes(String title, String prompt) {
        List<SceneDto> scenes = new ArrayList<>();
        String shortTitle = truncate(title != null && !title.isBlank() ? title : prompt, 28);

        SceneDto s1 = new SceneDto();
        s1.setId("s1");
        s1.setType("hook");
        s1.setNarration("先问一个关键问题：" + truncate(prompt, 60));
        SceneProps p1 = new SceneProps();
        p1.setEyebrow("洞察开场");
        p1.setTitle(shortTitle);
        p1.setSubtitle("差的是路径，不是信息量");
        s1.setProps(p1);
        scenes.add(s1);

        SceneDto s2 = new SceneDto();
        s2.setId("s2");
        s2.setType("compare");
        s2.setNarration("左边是常见误区，右边是更有效的做法。结合主题：" + truncate(prompt, 50));
        SceneProps p2 = new SceneProps();
        p2.setHeading("两种路径对比");
        p2.setLeftLabel("无效路径");
        p2.setRightLabel("有效路径");
        p2.setLeftItems(List.of("只堆概念", "没有验收标准", "频繁换工具"));
        p2.setRightItems(List.of("锁定一个场景", "最小可运行版本", "用结果迭代"));
        s2.setProps(p2);
        scenes.add(s2);

        SceneDto s3 = new SceneDto();
        s3.setId("s3");
        s3.setType("metric");
        s3.setNarration("把范围收窄之后，往往两周内就能做出可演示的第一版。");
        SceneProps p3 = new SceneProps();
        p3.setValue("14");
        p3.setUnit("天");
        p3.setLabel("从零到可演示");
        p3.setHint("范围足够小的时候");
        s3.setProps(p3);
        scenes.add(s3);

        SceneDto s4 = new SceneDto();
        s4.setId("s4");
        s4.setType("insight");
        s4.setNarration("记住三件事：场景优先、闭环优先、结果优先。");
        SceneProps p4 = new SceneProps();
        p4.setHeading("落地三原则");
        p4.setItems(List.of("先选场景", "最小闭环", "用结果迭代"));
        s4.setProps(p4);
        scenes.add(s4);

        SceneDto s5 = new SceneDto();
        s5.setId("s5");
        s5.setType("outro");
        s5.setNarration("先选场景，再选工具。感谢观看。");
        SceneProps p5 = new SceneProps();
        p5.setTitle(shortTitle);
        p5.setCta("关注 · 下期继续");
        s5.setProps(p5);
        scenes.add(s5);
        return scenes;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
