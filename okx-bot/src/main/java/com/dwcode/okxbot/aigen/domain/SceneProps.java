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

    /** 洞察对比等：小标签 / 眉题 */
    private String eyebrow;
    /** 左右对比 */
    private String leftLabel;
    private String rightLabel;
    @JsonDeserialize(using = FlexibleStringListDeserializer.class)
    private List<String> leftItems = new ArrayList<>();
    @JsonDeserialize(using = FlexibleStringListDeserializer.class)
    private List<String> rightItems = new ArrayList<>();
    /** 大数字场景 */
    private String value;
    private String unit;
    private String label;
    private String hint;
}
