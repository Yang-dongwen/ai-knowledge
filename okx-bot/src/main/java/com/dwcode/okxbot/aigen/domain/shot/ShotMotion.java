package com.dwcode.okxbot.aigen.domain.shot;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class ShotMotion {
    /** static | ken_burns | zoom_in | zoom_out */
    private String type = "ken_burns";
    private Map<String, Object> params = new HashMap<>();
}
