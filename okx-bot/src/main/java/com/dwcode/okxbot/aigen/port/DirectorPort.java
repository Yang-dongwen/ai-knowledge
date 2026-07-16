package com.dwcode.okxbot.aigen.port;

import com.dwcode.okxbot.aigen.domain.shot.ShotlistDto;

/**
 * Visual Timeline 导演：主题 → 镜头表。
 */
public interface DirectorPort {
    ShotlistDto plan(DirectorCommand command);
}
