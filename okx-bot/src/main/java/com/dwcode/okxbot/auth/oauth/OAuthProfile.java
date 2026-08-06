package com.dwcode.okxbot.auth.oauth;

import lombok.Builder;
import lombok.Data;

/**
 * 归一化后的第三方用户资料（JustAuth / mock）。
 */
@Data
@Builder
public class OAuthProfile {
    private OAuthProvider provider;
    private String providerUserId;
    private String email;
    private String displayName;
    private String avatarUrl;
}
