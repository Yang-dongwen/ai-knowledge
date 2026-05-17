package com.dwcode.okxbot.okx.config;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * OKX 配置实体。
 */
@Data
@TableName("okx_config")
public class OkxConfigEntity {

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String apiKeyMasked;

    private String apiKeyEncrypted;

    private String secretKeyEncrypted;

    private String passphraseEncrypted;

    /** 是否模拟盘 1是 0否 */
    private Integer simulated;

    private String status;

    private LocalDateTime lastCheckAt;

    private String lastError;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
