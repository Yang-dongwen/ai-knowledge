package com.dwcode.okxbot.auth.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.auth.entity.SysUserEntity;
import com.dwcode.okxbot.auth.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final SysUserMapper sysUserMapper;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        SysUserEntity user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUserEntity>().eq(SysUserEntity::getEmail, email.trim().toLowerCase())
        );
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        return new AuthUserPrincipal(user);
    }

    public AuthUserPrincipal loadById(Long userId) {
        SysUserEntity user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        return new AuthUserPrincipal(user);
    }
}
