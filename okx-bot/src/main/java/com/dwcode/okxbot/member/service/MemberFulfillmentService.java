package com.dwcode.okxbot.member.service;

import com.dwcode.okxbot.auth.entity.SysUserEntity;
import com.dwcode.okxbot.auth.enums.UserRole;
import com.dwcode.okxbot.auth.mapper.SysUserMapper;
import com.dwcode.okxbot.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 唯一写 sys_user.role / member_expire_at 的开通履约入口（支付成功）。
 * 必须在事务内 + 用户行锁，防止并发丢天数。
 * <p>不依赖 pay 包，避免循环依赖：调用方传入 userId/days/orderNo。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberFulfillmentService {

    private final SysUserMapper sysUserMapper;

    /**
     * 按订单快照叠加会员天数。调用方须与订单 SUCCESS/fulfilled 同事务。
     */
    @Transactional
    public void grantByOrder(Long userId, int durationDays, String orderNo) {
        if (userId == null) {
            throw new BusinessException(500, "履约用户无效");
        }
        if (durationDays <= 0) {
            throw new BusinessException(500, "订单时长无效");
        }

        SysUserEntity user = sysUserMapper.selectByIdForUpdate(userId);
        if (user == null) {
            throw new BusinessException(500, "用户不存在，无法履约: " + userId);
        }

        // 超管不应产生付费单；误触则跳过改 role/expire
        if (UserRole.SUPER_ADMIN.name().equals(user.getRole())) {
            log.warn("跳过超管履约: orderNo={}, userId={}", orderNo, user.getId());
            return;
        }

        LocalDateTime now = LocalDateTime.now(MemberStatusService.ZONE);
        LocalDateTime base = user.getMemberExpireAt() != null && user.getMemberExpireAt().isAfter(now)
                ? user.getMemberExpireAt()
                : now;
        LocalDateTime neu = base.plusDays(durationDays);

        user.setRole(UserRole.MEMBER.name());
        user.setMemberExpireAt(neu);
        user.setUpdatedAt(now);
        sysUserMapper.updateById(user);

        log.info("member granted orderNo={} userId={} days={} expireAt={}",
                orderNo, user.getId(), durationDays, neu);
    }

    /**
     * 运维手工设定会员（绝对 expire + role）；V1 退款/延期用。
     */
    @Transactional
    public void adminSetMember(Long userId, String role, LocalDateTime memberExpireAt) {
        SysUserEntity user = sysUserMapper.selectByIdForUpdate(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        if (UserRole.SUPER_ADMIN.name().equals(user.getRole())) {
            throw new BusinessException(400, "不能通过会员接口修改超级管理员角色");
        }
        UserRole target = UserRole.from(role);
        if (target == UserRole.SUPER_ADMIN) {
            throw new BusinessException(400, "不能通过会员接口提升为超级管理员");
        }
        LocalDateTime now = LocalDateTime.now(MemberStatusService.ZONE);
        user.setRole(target.name());
        user.setMemberExpireAt(memberExpireAt);
        user.setUpdatedAt(now);
        sysUserMapper.updateById(user);
        log.info("admin set member userId={} role={} expireAt={}", userId, target, memberExpireAt);
    }
}
