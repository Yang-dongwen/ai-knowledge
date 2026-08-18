package com.dwcode.okxbot.horizon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("horizon_digest")
public class HorizonDigestEntity {

    @TableId(type = IdType.INPUT)
    private String digestDate;

    private String lang;
    private String title;
    private String markdown;
    private String snippet;
    private String haloPostName;
    private String haloPermalink;
    private LocalDateTime updatedAt;
}
