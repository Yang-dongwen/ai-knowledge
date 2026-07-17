package com.dwcode.okxbot.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.auth.config.AuthProperties;
import com.dwcode.okxbot.auth.dto.*;
import com.dwcode.okxbot.auth.entity.SysUserEntity;
import com.dwcode.okxbot.auth.enums.EmailCodePurpose;
import com.dwcode.okxbot.auth.enums.UserRole;
import com.dwcode.okxbot.auth.mapper.SysUserMapper;
import com.dwcode.okxbot.auth.security.AuthUserPrincipal;
import com.dwcode.okxbot.auth.security.JwtService;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.member.service.MemberStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final EmailCodeService emailCodeService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthProperties authProperties;
    private final MemberStatusService memberStatusService;

    public void sendRegisterCode(String email) {
        String normalized = EmailCodeService.normalizeEmail(email);
        SysUserEntity exists = findByEmail(normalized);
        if (exists != null) {
            // 防枚举：仍可返回成功语义，但这里明确提示更符合产品
            throw new BusinessException(400, "该邮箱已注册，请直接登录");
        }
        emailCodeService.sendCode(normalized, EmailCodePurpose.REGISTER);
    }

    @Transactional
    public LoginResponse register(RegisterRequest request) {
        String email = EmailCodeService.normalizeEmail(request.getEmail());
        if (findByEmail(email) != null) {
            throw new BusinessException(400, "该邮箱已注册，请直接登录");
        }
        emailCodeService.verifyAndConsume(email, request.getCode(), EmailCodePurpose.REGISTER);

        SysUserEntity user = new SysUserEntity();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        String nickname = request.getNickname();
        if (nickname == null || nickname.isBlank()) {
            nickname = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        }
        user.setNickname(nickname.trim());
        // 新注册默认普通用户；会员靠后续充值升级
        user.setRole(UserRole.USER.name());
        user.setStatus(1);
        user.setEmailVerified(1);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.insert(user);

        log.info("用户注册成功: email={}, id={}", email, user.getId());
        return buildLoginResponse(user);
    }

    public LoginResponse login(LoginRequest request) {
        String email = EmailCodeService.normalizeEmail(request.getEmail());
        SysUserEntity user = findByEmail(email);
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(400, "邮箱或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(403, "账号已被禁用");
        }
        if (user.getEmailVerified() == null || user.getEmailVerified() != 1) {
            throw new BusinessException(403, "邮箱未验证，请完成验证后再登录");
        }
        user.setLastLoginAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
        log.info("用户登录: email={}, id={}", email, user.getId());
        return buildLoginResponse(user);
    }

    public void sendResetCode(String email) {
        String normalized = EmailCodeService.normalizeEmail(email);
        SysUserEntity user = findByEmail(normalized);
        // 防枚举：用户不存在也假装已发送
        if (user == null) {
            log.info("找回密码：邮箱未注册，跳过发送: {}", normalized);
            return;
        }
        emailCodeService.sendCode(normalized, EmailCodePurpose.RESET_PASSWORD);
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        String email = EmailCodeService.normalizeEmail(request.getEmail());
        SysUserEntity user = findByEmail(email);
        if (user == null) {
            throw new BusinessException(400, "验证码错误或已过期");
        }
        emailCodeService.verifyAndConsume(email, request.getCode(), EmailCodePurpose.RESET_PASSWORD);
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
        log.info("密码已重置: email={}", email);
    }

    /**
     * 查询当前登录用户完整资料（走数据库，非 JWT 缓存字段）。
     * 先惰性降级过期 MEMBER，再返回 memberActive。
     */
    public AuthUserResponse me() {
        AuthUserPrincipal p = SecurityUtils.requireCurrentUser();
        memberStatusService.demoteIfExpired(p.getId());
        SysUserEntity user = sysUserMapper.selectById(p.getId());
        if (user == null) {
            throw new BusinessException(401, "用户不存在或已删除");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(403, "账号已被禁用");
        }
        return toAuthUserResponse(user);
    }

    private LoginResponse buildLoginResponse(SysUserEntity user) {
        String token = jwtService.generateToken(user.getId(), user.getEmail());
        return LoginResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(authProperties.getJwt().getExpireSeconds())
                .user(toAuthUserResponse(user))
                .build();
    }

    private AuthUserResponse toAuthUserResponse(SysUserEntity user) {
        UserRole role = UserRole.from(user.getRole());
        return AuthUserResponse.builder()
                .id(String.valueOf(user.getId()))
                .email(user.getEmail())
                .nickname(user.getNickname())
                .role(role.name())
                .roleLabel(role.displayName())
                .emailVerified(user.getEmailVerified() != null && user.getEmailVerified() == 1)
                .status(user.getStatus())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .memberExpireAt(user.getMemberExpireAt())
                .memberActive(memberStatusService.isActive(user))
                .build();
    }

    private SysUserEntity findByEmail(String email) {
        return sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUserEntity>().eq(SysUserEntity::getEmail, email)
        );
    }
}
