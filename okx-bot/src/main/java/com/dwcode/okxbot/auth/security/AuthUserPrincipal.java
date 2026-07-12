package com.dwcode.okxbot.auth.security;

import com.dwcode.okxbot.auth.entity.SysUserEntity;
import com.dwcode.okxbot.auth.enums.UserRole;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Security 上下文中的当前用户。
 */
@Getter
public class AuthUserPrincipal implements UserDetails {

    private final Long id;
    private final String email;
    private final String passwordHash;
    private final String nickname;
    private final UserRole role;
    private final boolean enabled;
    private final boolean emailVerified;

    public AuthUserPrincipal(SysUserEntity user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.passwordHash = user.getPasswordHash();
        this.nickname = user.getNickname();
        this.role = UserRole.from(user.getRole());
        this.enabled = user.getStatus() != null && user.getStatus() == 1;
        this.emailVerified = user.getEmailVerified() != null && user.getEmailVerified() == 1;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Spring hasRole 自动加 ROLE_ 前缀，此处显式 ROLE_xxx
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    public boolean isSuperAdmin() {
        return role.isSuperAdmin();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
