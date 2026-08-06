package com.dwcode.okxbot.member.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dwcode.okxbot.auth.entity.SysUserEntity;
import com.dwcode.okxbot.auth.enums.UserRole;
import com.dwcode.okxbot.auth.mapper.SysUserMapper;
import com.dwcode.okxbot.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 有效会员判定唯一入口。禁止业务侧仅用 role==MEMBER 做权益门闸。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberStatusService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final SysUserMapper sysUserMapper;

    /**
     * 唯一权益判定：SUPER_ADMIN → true；否则 MEMBER 且 memberExpireAt &gt; now。
     */
    public static boolean isActive(String role, LocalDateTime memberExpireAt, LocalDateTime now) {
        UserRole r = UserRole.from(role);
        if (r == UserRole.SUPER_ADMIN) {
            return true;
        }
        if (r != UserRole.MEMBER) {
            return false;
        }
        return memberExpireAt != null && memberExpireAt.isAfter(now);
    }

    public boolean isActive(SysUserEntity user) {
        if (user == null) {
            return false;
        }
        return isActive(user.getRole(), user.getMemberExpireAt(), LocalDateTime.now(ZONE));
    }

    public boolean isActive(Long userId) {
        SysUserEntity user = sysUserMapper.selectById(userId);
        return isActive(user);
    }

    /**
     * me()/权益点：若 MEMBER 且已过期 → role=USER（保留 expire）；SUPER_ADMIN 跳过。
     */
    @Transactional
    public void demoteIfExpired(Long userId) {
        if (userId == null) {
            return;
        }
        SysUserEntity user = sysUserMapper.selectById(userId);
        if (user == null) {
            return;
        }
        UserRole role = UserRole.from(user.getRole());
        if (role != UserRole.MEMBER) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZONE);
        if (user.getMemberExpireAt() != null && user.getMemberExpireAt().isAfter(now)) {
            return;
        }
        // 原子条件：必须仍过期，避免与支付履约 grant 竞态把刚续费用户打回 USER
        int n = sysUserMapper.update(null, new LambdaUpdateWrapper<SysUserEntity>()
                .eq(SysUserEntity::getId, userId)
                .eq(SysUserEntity::getRole, UserRole.MEMBER.name())
                .and(w -> w.isNull(SysUserEntity::getMemberExpireAt)
                        .or()
                        .le(SysUserEntity::getMemberExpireAt, now))
                .set(SysUserEntity::getRole, UserRole.USER.name())
                .set(SysUserEntity::getUpdatedAt, now));
        if (n > 0) {
            log.info("会员已过期，降级为 USER: userId={}, expireAt={}", userId, user.getMemberExpireAt());
        }
    }

    /**
     * 批量兜底降级（定时任务）。
     */
    @Transactional
    public int demoteAllExpired() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        int n = sysUserMapper.update(null, new LambdaUpdateWrapper<SysUserEntity>()
                .eq(SysUserEntity::getRole, UserRole.MEMBER.name())
                .and(w -> w.isNull(SysUserEntity::getMemberExpireAt)
                        .or()
                        .le(SysUserEntity::getMemberExpireAt, now))
                .set(SysUserEntity::getRole, UserRole.USER.name())
                .set(SysUserEntity::getUpdatedAt, now));
        if (n > 0) {
            log.info("MemberExpireJob 批量降级 {} 人", n);
        }
        return n;
    }

    /**
     * AI 等付费能力入口门闸。知识库等免费能力请勿调用。
     * 文案固定便于前端匹配升级引导。
     */
    public void requireActiveMember(Long userId) {
        demoteIfExpired(userId);
        SysUserEntity user = sysUserMapper.selectById(userId);
        if (!isActive(user)) {
            throw new BusinessException(403, "需要有效会员才能使用此功能，请先开通会员");
        }
    }

    public void requireActiveMember() {
        requireActiveMember(com.dwcode.okxbot.auth.security.SecurityUtils.requireCurrentUserId());
    }

    /**
     * 统计过期待降级数量（观测用）。
     */
    public long countExpiredMembers() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        Long c = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUserEntity>()
                .eq(SysUserEntity::getRole, UserRole.MEMBER.name())
                .and(w -> w.isNull(SysUserEntity::getMemberExpireAt)
                        .or()
                        .le(SysUserEntity::getMemberExpireAt, now)));
        return c == null ? 0 : c;
    }
}
