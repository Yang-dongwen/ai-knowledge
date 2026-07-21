package com.dwcode.okxbot.storage.migration;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 旧本地媒体迁移选项。
 */
@Data
@Builder
public class MigrationOptions {

    /** 要迁移的模块：video / aigen / imggen；空=全部 */
    private List<String> modules;

    /** 只统计不写存储、不改库 */
    @Builder.Default
    private boolean dryRun = true;

    /** put+校验成功后删除本地文件/目录 */
    @Builder.Default
    private boolean deleteLocal = false;

    /** 每个模块最多处理任务数；0=不限制 */
    @Builder.Default
    private int limitPerModule = 0;

    /** 仅迁移该 taskId（可选） */
    private String taskId;
}
