package com.dwcode.okxbot.aigen.domain;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SceneProps {
    private String title;
    private String subtitle;
    private String heading;
    /**
     * 要点列表。LLM 有时会返回对象数组（如 {"text":"..."}），用宽松反序列化兼容。
     */
    @JsonDeserialize(using = FlexibleStringListDeserializer.class)
    private List<String> items = new ArrayList<>();
    private String cta;
}
