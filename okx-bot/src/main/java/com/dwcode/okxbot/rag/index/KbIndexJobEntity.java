package com.dwcode.okxbot.rag.index;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("kb_index_job")
public class KbIndexJobEntity {

    public static final String TYPE_NOTE = "NOTE";
    public static final String TYPE_FILE = "FILE";
    public static final String OP_UPSERT = "UPSERT";
    public static final String OP_DELETE = "DELETE";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAIL = "FAIL";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String sourceType;
    private Long sourceId;
    private String op;
    private String status;
    private Integer attempts;
    private LocalDateTime nextRunAt;
    private String lastError;
    private String contentHash;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
