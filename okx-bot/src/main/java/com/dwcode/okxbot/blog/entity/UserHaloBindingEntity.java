package com.dwcode.okxbot.blog.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_halo_binding")
public class UserHaloBindingEntity {

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.INPUT)
    private Long userId;

    private String baseUrl;
    private String publicBaseUrl;
    private String tokenCipher;
    private String haloUsername;
    private LocalDateTime verifiedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
