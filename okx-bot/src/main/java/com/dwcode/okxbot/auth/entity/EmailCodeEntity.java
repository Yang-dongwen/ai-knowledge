package com.dwcode.okxbot.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 邮箱验证码。
 */
@Data
@TableName("email_code")
public class EmailCodeEntity {

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String email;
    private String code;
    /** REGISTER / RESET_PASSWORD */
    private String purpose;
    private LocalDateTime expiresAt;
    /** 0 未使用 1 已使用 */
    private Integer used;
    private LocalDateTime createdAt;
}
