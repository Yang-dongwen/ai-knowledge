package com.dwcode.okxbot.auth.oauth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dwcode.okxbot.auth.oauth.entity.UserOAuthBindingEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserOAuthBindingMapper extends BaseMapper<UserOAuthBindingEntity> {
}
