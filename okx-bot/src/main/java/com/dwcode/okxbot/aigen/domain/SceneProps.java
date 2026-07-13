package com.dwcode.okxbot.aigen.domain;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SceneProps {
    private String title;
    private String subtitle;
    private String heading;
    private List<String> items = new ArrayList<>();
    private String cta;
}
