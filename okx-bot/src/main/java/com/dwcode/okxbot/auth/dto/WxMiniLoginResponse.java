package com.dwcode.okxbot.auth.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 微信小程序登录结果。
 * <ul>
 *   <li>needBind=false：已绑定，返回 JWT</li>
 *   <li>needBind=true：需用邮箱密码绑定一次</li>
 * </ul>
 */
@Data
@Builder
public class WxMiniLoginResponse {

    private boolean needBind;

    private String token;
    private String tokenType;
    private Long expiresIn;
    private AuthUserResponse user;
}
