package com.dwcode.okxbot.aigen.domain.shot;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ShotlistAudio {
    /** none | bgm_only | tts */
    private String mode = "none";
    private String bgmId;
    /** 相对任务目录，如 assets/audio/bgm.mp3 */
    private String bgmSrc;
    private String ttsVoice;
    private List<String> ttsEnabledShots = new ArrayList<>();
}
