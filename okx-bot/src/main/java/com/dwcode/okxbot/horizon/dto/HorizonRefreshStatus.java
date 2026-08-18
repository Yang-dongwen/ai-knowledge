package com.dwcode.okxbot.horizon.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class HorizonRefreshStatus {

    private boolean enabled;
    private boolean running;
    private LocalDateTime lastStartedAt;
    private LocalDateTime lastFinishedAt;
    private boolean lastOk;
    private String lastMessage;
    private boolean lastPublished;
    private String lastPermalink;
}
