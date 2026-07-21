package com.dwcode.okxbot.storage;

import lombok.Builder;
import lombok.Data;

/**
 * 对象元数据（Head 结果）。
 */
@Data
@Builder
public class ObjectMeta {
    private String key;
    private long sizeBytes;
    private String contentType;
    /** epoch millis；未知为 0 */
    private long lastModifiedEpochMs;
}
