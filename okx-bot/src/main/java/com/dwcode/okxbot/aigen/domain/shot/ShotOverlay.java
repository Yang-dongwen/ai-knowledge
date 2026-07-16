package com.dwcode.okxbot.aigen.domain.shot;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ShotOverlay {
    /** none | hook-center | lower-third | bullets-right | caption */
    private String layout = "hook-center";
    private String title;
    private String subtitle;
    private List<String> bullets = new ArrayList<>();
    private String position = "center";
    private String style = "bold-impact";
}
