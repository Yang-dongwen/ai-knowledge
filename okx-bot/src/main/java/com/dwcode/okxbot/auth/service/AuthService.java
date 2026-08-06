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
import com.dwcode.okxbot.auth.wechat.WxMiniSessionClient;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.member.service.MemberStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
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
    private final WxMiniSessionClient wxMiniSessionClient;
    private final LoginRateLimiter loginRateLimiter;

    public void sendRegisterCode(String email) {
        String normalized = EmailCodeService.normalizeEmail(email);
        SysUserEntity exists = findByEmail(normalized);
        if (exists != null) {
            // 防邮箱枚举：与找回密码一致，不暴露是否已注册
            log.info("注册验证码：邮箱已注册，跳过发送: {}", normalized);
            return;
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
        user.setTokenVersion(0);
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
        String clientIp = currentClientIp();
        loginRateLimiter.assertAllowed(email, clientIp);
        SysUserEntity user = findByEmail(email);
        if (user == null) {
            loginRateLimiter.recordFailure(email, clientIp);
            throw new BusinessException(400, "邮箱或密码错误");
        }
        if (!StringUtils.hasText(user.getPasswordHash())) {
            loginRateLimiter.recordFailure(email, clientIp);
            throw new BusinessException(400, "该账号仅支持第三方登录");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            loginRateLimiter.recordFailure(email, clientIp);
            throw new BusinessException(400, "邮箱或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(403, "账号已被禁用");
        }
        if (user.getEmailVerified() == null || user.getEmailVerified() != 1) {
            throw new BusinessException(403, "邮箱未验证，请完成验证后再登录");
        }
        loginRateLimiter.recordSuccess(email, clientIp);
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
        // 吊销既有 JWT
        int next = (user.getTokenVersion() != null ? user.getTokenVersion() : 0) + 1;
        user.setTokenVersion(next);
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
        log.info("密码已重置: email={} tokenVersion={}", email, next);
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

    /**
     * 微信小程序登录：已绑定 openid 则发 JWT，否则 needBind。
     */
    public WxMiniLoginResponse wxMiniLogin(String code) {
        String openid = wxMiniSessionClient.resolveOpenid(code);
        SysUserEntity user = findByWxMiniOpenid(openid);
        if (user == null) {
            log.info("wx mini login need bind openid={}", maskOpenid(openid));
            return WxMiniLoginResponse.builder().needBind(true).build();
        }
        assertUserCanLogin(user);
        touchLogin(user);
        log.info("wx mini login ok userId={} openid={}", user.getId(), maskOpenid(openid));
        return toWxMiniLoginResponse(user);
    }

    /**
     * 用邮箱密码绑定 openid 并登录（首次微信登录）。
     */
    @Transactional
    public WxMiniLoginResponse wxMiniBind(WxMiniBindRequest request) {
        String openid = wxMiniSessionClient.resolveOpenid(request.getCode());
        SysUserEntity byOpenid = findByWxMiniOpenid(openid);
        if (byOpenid != null) {
            // 已绑定：直接登录（幂等）
            assertUserCanLogin(byOpenid);
            touchLogin(byOpenid);
            return toWxMiniLoginResponse(byOpenid);
        }

        String email = EmailCodeService.normalizeEmail(request.getEmail());
        String clientIp = currentClientIp();
        loginRateLimiter.assertAllowed(email, clientIp);
        SysUserEntity user = findByEmail(email);
        if (user == null || !StringUtils.hasText(user.getPasswordHash())
                || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            loginRateLimiter.recordFailure(email, clientIp);
            throw new BusinessException(400, "邮箱或密码错误");
        }
        loginRateLimiter.recordSuccess(email, clientIp);
        assertUserCanLogin(user);

        if (StringUtils.hasText(user.getWxMiniOpenid())
                && !openid.equals(user.getWxMiniOpenid())) {
            throw new BusinessException(409, "该账号已绑定其他微信，请先在「我的」解绑");
        }

        user.setWxMiniOpenid(openid);
        touchLogin(user);
        log.info("wx mini bound userId={} openid={}", user.getId(), maskOpenid(openid));
        return toWxMiniLoginResponse(user);
    }

    /**
     * 已登录用户绑定当前微信（可选二次绑定入口）。
     */
    @Transactional
    public AuthUserResponse wxMiniBindCurrent(String code) {
        Long userId = SecurityUtils.requireCurrentUserId();
        String openid = wxMiniSessionClient.resolveOpenid(code);
        SysUserEntity occupied = findByWxMiniOpenid(openid);
        if (occupied != null && !occupied.getId().equals(userId)) {
            throw new BusinessException(409, "该微信已绑定其他账号");
        }
        SysUserEntity user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(401, "用户不存在或已删除");
        }
        if (StringUtils.hasText(user.getWxMiniOpenid())
                && !openid.equals(user.getWxMiniOpenid())) {
            throw new BusinessException(409, "当前账号已绑定其他微信，请先解绑");
        }
        user.setWxMiniOpenid(openid);
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
        log.info("wx mini bind-current userId={} openid={}", userId, maskOpenid(openid));
        return toAuthUserResponse(user);
    }

    /**
     * 解绑微信小程序。
     */
    @Transactional
    public AuthUserResponse wxMiniUnbind() {
        Long userId = SecurityUtils.requireCurrentUserId();
        SysUserEntity user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(401, "用户不存在或已删除");
        }
        user.setWxMiniOpenid(null);
        user.setUpdatedAt(LocalDateTime.now());
        // MyBatis-Plus 默认忽略 null 字段，需显式 update wrapper
        sysUserMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SysUserEntity>()
                .eq(SysUserEntity::getId, userId)
                .set(SysUserEntity::getWxMiniOpenid, null)
                .set(SysUserEntity::getUpdatedAt, LocalDateTime.now()));
        user.setWxMiniOpenid(null);
        log.info("wx mini unbound userId={}", userId);
        return toAuthUserResponse(user);
    }

    /** OAuth / 微信等外部登录复用 */
    public void assertUserCanLogin(SysUserEntity user) {
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(403, "账号已被禁用");
        }
        if (user.getEmailVerified() == null || user.getEmailVerified() != 1) {
            throw new BusinessException(403, "邮箱未验证，请完成验证后再登录");
        }
    }

    /** OAuth / 微信等外部登录复用 */
    public void touchLogin(SysUserEntity user) {
        user.setLastLoginAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
    }

    /** OAuth ticket 兑换等复用 */
    public LoginResponse buildLoginResponse(SysUserEntity user) {
        int tv = user.getTokenVersion() != null ? user.getTokenVersion() : 0;
        String token = jwtService.generateToken(user.getId(), user.getEmail(), tv);
        return LoginResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(authProperties.getJwt().getExpireSeconds())
                .user(toAuthUserResponse(user))
                .build();
    }

    private static String currentClientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return "unknown";
            }
            HttpServletRequest req = attrs.getRequest();
            String xff = req.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(xff)) {
                return xff.split(",")[0].trim();
            }
            String real = req.getHeader("X-Real-IP");
            if (StringUtils.hasText(real)) {
                return real.trim();
            }
            return req.getRemoteAddr() != null ? req.getRemoteAddr() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private WxMiniLoginResponse toWxMiniLoginResponse(SysUserEntity user) {
        LoginResponse lr = buildLoginResponse(user);
        return WxMiniLoginResponse.builder()
                .needBind(false)
                .token(lr.getToken())
                .tokenType(lr.getTokenType())
                .expiresIn(lr.getExpiresIn())
                .user(lr.getUser())
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
                .wxMiniBound(StringUtils.hasText(user.getWxMiniOpenid()))
                .build();
    }

    private SysUserEntity findByEmail(String email) {
        return sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUserEntity>().eq(SysUserEntity::getEmail, email)
        );
    }

    private SysUserEntity findByWxMiniOpenid(String openid) {
        if (!StringUtils.hasText(openid)) {
            return null;
        }
        return sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUserEntity>().eq(SysUserEntity::getWxMiniOpenid, openid)
        );
    }

    private static String maskOpenid(String openid) {
        if (!StringUtils.hasText(openid) || openid.length() < 8) {
            return "***";
        }
        return openid.substring(0, 4) + "…" + openid.substring(openid.length() - 4);
    }
}
