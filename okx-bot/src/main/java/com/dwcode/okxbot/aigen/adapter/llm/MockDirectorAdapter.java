package com.dwcode.okxbot.aigen.adapter.llm;

import com.dwcode.okxbot.aigen.domain.shot.*;
import com.dwcode.okxbot.aigen.port.DirectorCommand;
import com.dwcode.okxbot.aigen.port.DirectorPort;
import com.dwcode.okxbot.aigen.service.ShotlistNormalizeService;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * mock 导演：固定 5 镜，便于无 LLM 联调 VisualTimeline。
 */
@RequiredArgsConstructor
public class MockDirectorAdapter implements DirectorPort {

    private final ShotlistNormalizeService normalizeService;

    @Override
    public ShotlistDto plan(DirectorCommand command) {
        ShotlistDto list = new ShotlistDto();
        list.setShots(new ArrayList<>());
        String topic = command.getPrompt() != null ? command.getPrompt().trim() : "主题";
        if (topic.length() > 40) {
            topic = topic.substring(0, 40);
        }

        String[] titles = {"开场", "展开", "对比", "洞察", "收束"};
        String[] layouts = {"hook-center", "lower-third", "bullets-right", "caption", "hook-center"};
        for (int i = 0; i < 5; i++) {
            ShotDto s = new ShotDto();
            s.setId("shot-" + (i + 1));
            s.setDurationSec(command.getTargetDurationSec() / 5.0);
            ShotVisual v = new ShotVisual();
            v.setType("ai_image");
            v.setPrompt("cinematic still, " + topic + ", shot " + (i + 1) + ", dramatic lighting, no text");
            s.setVisual(v);
            ShotOverlay o = new ShotOverlay();
            o.setLayout(layouts[i]);
            o.setTitle(titles[i] + " · " + topic);
            if (i == 2) {
                o.setBullets(List.of("要点 A", "要点 B", "要点 C"));
            }
            s.setOverlay(o);
            s.setNarration(titles[i] + "。" + topic + "，请看这一镜。");
            list.getShots().add(s);
        }

        return normalizeService.normalize(
                list,
                command.getAspectRatio(),
                command.getTargetDurationSec(),
                command.getLanguage(),
                command.getStylePreset(),
                command.getAudioMode(),
                command.getTitleHint()
        );
    }
}
