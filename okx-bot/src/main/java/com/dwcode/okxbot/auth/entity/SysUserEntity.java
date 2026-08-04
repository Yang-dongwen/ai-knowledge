package com.dwcode.okxbot.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户（邮箱即登录名）。
 */
@Data
@TableName("sys_user")
public class SysUserEntity {

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 邮箱（唯一，用户名） */
    private String email;

    /** BCrypt 密码哈希 */
    private String passwordHash;

    private String nickname;

    /**
     * 角色：USER / MEMBER / SUPER_ADMIN
     * @see com.dwcode.okxbot.auth.enums.UserRole
     */
    private String role;

    /** 会员到期时间；过期后可保留用于展示 */
    private LocalDateTime memberExpireAt;

    /** 1 正常 0 禁用 */
    private Integer status;

    /** 1 邮箱已验证 */
    private Integer emailVerified;

    /** 微信小程序 openid（可空，唯一） */
    private String wxMiniOpenid;

    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
