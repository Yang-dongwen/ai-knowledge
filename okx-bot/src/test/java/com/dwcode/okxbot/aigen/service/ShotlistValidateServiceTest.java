package com.dwcode.okxbot.aigen.service;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.domain.shot.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShotlistValidateServiceTest {

    private ShotlistValidateService service;

    @BeforeEach
    void setUp() {
        AigenProperties props = new AigenProperties();
        props.setMinDurationSec(5);
        props.setMaxDurationSec(90);
        service = new ShotlistValidateService(props);
    }

    @Test
    void validMinimalShotlist() {
        ShotlistDto list = new ShotlistDto();
        list.setShots(new ArrayList<>());
        for (int i = 0; i < 5; i++) {
            ShotDto s = new ShotDto();
            s.setId("shot-" + i);
            s.setDurationSec(4.0);
            ShotVisual v = new ShotVisual();
            v.setType("ai_image");
            v.setPrompt("cinematic landscape, blue hour, no text");
            s.setVisual(v);
            list.getShots().add(s);
        }
        List<String> errors = service.validate(list, 20);
        assertTrue(errors.isEmpty(), () -> String.join("; ", errors));
    }

    @Test
    void rejectsTooFewShots() {
        ShotlistDto list = new ShotlistDto();
        list.setShots(new ArrayList<>());
        ShotDto s = new ShotDto();
        s.setId("s1");
        s.setDurationSec(3.0);
        ShotVisual v = new ShotVisual();
        v.setType("ai_image");
        v.setPrompt("test prompt enough length");
        s.setVisual(v);
        list.getShots().add(s);
        List<String> errors = service.validate(list, 30);
        assertFalse(errors.isEmpty());
    }
}
