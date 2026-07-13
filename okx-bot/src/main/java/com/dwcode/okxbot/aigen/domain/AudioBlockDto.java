package com.dwcode.okxbot.aigen.domain;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AudioBlockDto {
    private String voiceId;
    private List<AudioTrackDto> tracks = new ArrayList<>();
}
