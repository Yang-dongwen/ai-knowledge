package com.dwcode.okxbot.aigen.service;

import com.dwcode.okxbot.aigen.domain.shot.ShotDto;
import com.dwcode.okxbot.aigen.domain.shot.ShotOverlay;
import com.dwcode.okxbot.aigen.entity.AigenTaskEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualShotAssetServiceNarrationTest {

    @Test
    void usesNarrationWhenPresent() {
        ShotDto s = new ShotDto();
        s.setNarration("  这是口播  ");
        String t = VisualShotAssetService.resolveNarrationText(s, null);
        assertTrue(t.contains("这是口播"));
    }

    @Test
    void fallsBackToOverlayAndBullets() {
        ShotDto s = new ShotDto();
        ShotOverlay o = new ShotOverlay();
        o.setTitle("标题");
        o.setSubtitle("副标题");
        o.setBullets(List.of("要点一"));
        s.setOverlay(o);
        String t = VisualShotAssetService.resolveNarrationText(s, null);
        assertTrue(t.contains("标题"));
        assertTrue(t.contains("副标题"));
        assertTrue(t.contains("要点一"));
    }

    @Test
    void neverReturnsBlank() {
        ShotDto s = new ShotDto();
        s.setId("shot-7");
        s.setOrder(7);
        AigenTaskEntity task = new AigenTaskEntity();
        task.setTitle("测试主题");
        String t = VisualShotAssetService.resolveNarrationText(s, task);
        assertFalse(t.isBlank());
        assertTrue(t.contains("7") || t.contains("测试"));
    }
}
