package com.dwcode.okxbot.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dwcode.okxbot.auth.entity.SysUserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUserEntity> {

    /**
     * 履约加锁：须在事务内调用，防止并发叠加有效期丢失。
     */
    @Select("SELECT * FROM sys_user WHERE id = #{id} FOR UPDATE")
    SysUserEntity selectByIdForUpdate(@Param("id") Long id);
}
