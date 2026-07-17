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

        boolean en = command.getLanguage() != null
                && command.getLanguage().trim().toLowerCase(java.util.Locale.ROOT).startsWith("en");
        String[] titles = en
                ? new String[]{"Breath", "Fracture", "Echo", "Rise", "Silence"}
                : new String[]{"呼吸", "裂痕", "回声", "抬升", "余韵"};
        String[] motions = {"drift", "punch_in", "whip", "orbit", "rise"};
        String[] layouts = {"none", "free", "none", "big-word", "none"};
        for (int i = 0; i < 5; i++) {
            ShotDto s = new ShotDto();
            s.setId("shot-" + (i + 1));
            s.setDurationSec(Math.max(2.4, command.getTargetDurationSec() / 5.0));
            ShotVisual v = new ShotVisual();
            v.setType("ai_image");
            v.setPrompt(en
                    ? ("cinematic still of " + topic + ", shot " + (i + 1)
                    + ", dramatic side light, shallow depth of field, film grain, no text")
                    : ("电影感静帧：" + topic + "，第" + (i + 1)
                    + "镜，侧逆光，浅景深，胶片质感，画面无文字"));
            s.setVisual(v);
            ShotMotion m = new ShotMotion();
            m.setType(motions[i]);
            s.setMotion(m);
            ShotOverlay o = new ShotOverlay();
            o.setLayout(layouts[i]);
            o.setTextAnim("pop");
            o.setStyle("cinematic");
            if (!"none".equals(layouts[i])) {
                o.setTitle(titles[i]);
            }
            s.setOverlay(o);
            s.setNarration(en
                    ? (titles[i] + ". " + topic + ".")
                    : (titles[i] + "。" + topic + "。"));
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
