package com.dwcode.okxbot.storage.migration;

import lombok.Data;

import java.util.List;

@Data
public class MigrationRequest {
    /** video / aigen / imggen；空=全部 */
    private List<String> modules;
    /** 默认 true：只报告不改动 */
    private Boolean dryRun = true;
    /** put 成功后删本地目录 */
    private Boolean deleteLocal = false;
    /** 每模块最多任务目录数；0=不限 */
    private Integer limitPerModule = 0;
    /** 只迁某一个 taskId */
    private String taskId;
}
