package com.dwcode.okxbot.aigen.service;

import com.dwcode.okxbot.aigen.domain.shot.ShotDto;
import com.dwcode.okxbot.aigen.domain.shot.ShotVisual;
import com.dwcode.okxbot.aigen.domain.shot.ShotlistDto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicRelevanceServiceTest {

    private final TopicRelevanceService service = new TopicRelevanceService();

    @Test
    void extractsEthereumAnchors() {
        List<String> anchors = service.extractAnchors("生成以太坊加密货币的历史进程 Ethereum");
        assertTrue(anchors.stream().anyMatch(a -> a.contains("以太坊") || a.equalsIgnoreCase("Ethereum")));
    }

    @Test
    void validateShotlistDetectsOffTopic() {
        ShotlistDto list = new ShotlistDto();
        list.setShots(new ArrayList<>());
        ShotDto s = new ShotDto();
        s.setId("shot-1");
        ShotVisual v = new ShotVisual();
        v.setPrompt("霓虹都市夜景，赛博朋克雾气");
        s.setVisual(v);
        list.getShots().add(s);
        List<String> errors = service.validateShotlist(list, "以太坊历史 Ethereum");
        assertFalse(errors.isEmpty());
    }

    @Test
    void validateShotlistAcceptsOnTopic() {
        ShotlistDto list = new ShotlistDto();
        list.setShots(new ArrayList<>());
        ShotDto s = new ShotDto();
        s.setId("shot-1");
        ShotVisual v = new ShotVisual();
        v.setPrompt("以太坊创世区块在深空爆发，金色链环");
        v.setPromptEn("Ethereum genesis block explosion");
        s.setVisual(v);
        list.getShots().add(s);
        List<String> errors = service.validateShotlist(list, "以太坊加密货币历史");
        assertTrue(errors.isEmpty(), () -> String.join("; ", errors));
    }
}
