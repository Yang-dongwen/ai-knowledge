package com.dwcode.okxbot.aigen.port;

import com.dwcode.okxbot.aigen.domain.StoryboardDto;

public interface ScriptPlanPort {
    StoryboardDto plan(PlanCommand command);
}
