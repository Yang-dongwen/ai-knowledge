package com.dwcode.okxbot.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dwcode.okxbot.auth.entity.EmailCodeEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EmailCodeMapper extends BaseMapper<EmailCodeEntity> {
}
