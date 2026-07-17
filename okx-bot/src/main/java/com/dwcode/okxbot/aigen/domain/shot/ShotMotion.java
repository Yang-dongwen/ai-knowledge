package com.dwcode.okxbot.aigen.domain.shot;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class ShotMotion {
    /**
     * 运镜类型（自由创意，非模板填空）：
     * static | ken_burns | zoom_in | zoom_out | pan_left | pan_right |
     * punch_in | punch_out | whip | drift | shake | orbit | tilt | rise | fall | auto
     */
    private String type = "auto";
    /**
     * 可选参数（Remotion 自由动效引擎解读）：
     * intensity, scaleFrom, scaleTo, xFrom, xTo, yFrom, yTo, rotateFrom, rotateTo
     */
    private Map<String, Object> params = new HashMap<>();
}
