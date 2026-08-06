package com.dwcode.okxbot.auth.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.auth.config.AuthProperties;
import com.dwcode.okxbot.auth.entity.SysUserEntity;
import com.dwcode.okxbot.auth.enums.UserRole;
import com.dwcode.okxbot.auth.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 启动时确保库中至少有一个超级管理员。
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class SuperAdminInitializer implements ApplicationRunner {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties authProperties;

    @Override
    public void run(ApplicationArguments args) {
        AuthProperties.Admin admin = authProperties.getAdmin();
        if (admin == null || !admin.isSeedEnabled()) {
            return;
        }
        try {
            Long count = sysUserMapper.selectCount(
                    new LambdaQueryWrapper<SysUserEntity>()
                            .eq(SysUserEntity::getRole, UserRole.SUPER_ADMIN.name())
            );
            if (count != null && count > 0) {
                log.debug("超级管理员已存在，跳过种子");
                return;
            }

            String email = admin.getEmail() == null ? "" : admin.getEmail().trim().toLowerCase();
            if (email.isEmpty() || admin.getPassword() == null || admin.getPassword().isBlank()) {
                log.warn("auth.admin 邮箱或密码未配置，无法种子超级管理员");
                return;
            }

            SysUserEntity existing = sysUserMapper.selectOne(
                    new LambdaQueryWrapper<SysUserEntity>().eq(SysUserEntity::getEmail, email)
            );
            if (existing != null) {
                existing.setRole(UserRole.SUPER_ADMIN.name());
                existing.setStatus(1);
                existing.setEmailVerified(1);
                existing.setUpdatedAt(LocalDateTime.now());
                if (existing.getNickname() == null || existing.getNickname().isBlank()) {
                    existing.setNickname(admin.getNickname());
                }
                sysUserMapper.updateById(existing);
                log.info("已将现有用户提升为超级管理员: email={}", email);
                return;
            }

            SysUserEntity user = new SysUserEntity();
            user.setEmail(email);
            user.setPasswordHash(passwordEncoder.encode(admin.getPassword()));
            user.setTokenVersion(0);
            user.setNickname(admin.getNickname() != null ? admin.getNickname() : "超级管理员");
            user.setRole(UserRole.SUPER_ADMIN.name());
            user.setStatus(1);
            user.setEmailVerified(1);
            user.setCreatedAt(LocalDateTime.now());
            user.setUpdatedAt(LocalDateTime.now());
            sysUserMapper.insert(user);
            log.warn("已创建默认超级管理员: email={} （请尽快修改默认密码）", email);
        } catch (Exception e) {
            // 表结构未迁移时不阻断启动
            log.error("种子超级管理员失败（请确认 sys_user.role 列已创建）: {}", e.getMessage());
        }
    }
}
