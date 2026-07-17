package com.dwcode.okxbot.member.service;

import com.dwcode.okxbot.auth.entity.SysUserEntity;
import com.dwcode.okxbot.auth.enums.UserRole;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class MemberStatusServiceTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 7, 17, 12, 0);

    @Test
    void superAdminAlwaysActive() {
        assertTrue(MemberStatusService.isActive(UserRole.SUPER_ADMIN.name(), null, now));
        assertTrue(MemberStatusService.isActive(
                UserRole.SUPER_ADMIN.name(), now.minusDays(1), now));
    }

    @Test
    void userNeverActive() {
        assertFalse(MemberStatusService.isActive(UserRole.USER.name(), now.plusDays(30), now));
        assertFalse(MemberStatusService.isActive(UserRole.USER.name(), null, now));
    }

    @Test
    void memberRequiresFutureExpire() {
        assertTrue(MemberStatusService.isActive(
                UserRole.MEMBER.name(), now.plusDays(1), now));
        assertFalse(MemberStatusService.isActive(
                UserRole.MEMBER.name(), now, now));
        assertFalse(MemberStatusService.isActive(
                UserRole.MEMBER.name(), now.minusSeconds(1), now));
        assertFalse(MemberStatusService.isActive(
                UserRole.MEMBER.name(), null, now));
    }

    @Test
    void entityHelper() {
        MemberStatusService svc = new MemberStatusService(null);
        SysUserEntity u = new SysUserEntity();
        u.setRole(UserRole.MEMBER.name());
        u.setMemberExpireAt(LocalDateTime.now(MemberStatusService.ZONE).plusDays(10));
        assertTrue(svc.isActive(u));
        u.setMemberExpireAt(LocalDateTime.now(MemberStatusService.ZONE).minusDays(1));
        assertFalse(svc.isActive(u));
    }
}
