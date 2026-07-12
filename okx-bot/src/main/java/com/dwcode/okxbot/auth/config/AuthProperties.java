package com.dwcode.okxbot.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    private Jwt jwt = new Jwt();
    private Mail mail = new Mail();
    private Code code = new Code();
    /** 首次启动若不存在超管则自动种子 */
    private Admin admin = new Admin();

    @Data
    public static class Jwt {
        /** HS256 密钥，生产环境务必使用足够长的随机串 */
        private String secret = "change-me-to-a-very-long-random-secret-key-for-jwt-hs256-okx-bot";
        /** 访问令牌有效期（秒），默认 2 小时 */
        private long expireSeconds = 7200;
        private String issuer = "okx-bot";
    }

    @Data
    public static class Mail {
        /**
         * true：不真实发信，验证码打印到日志（本地开发）。
         * false：使用 spring.mail 发送真实邮件。
         */
        private boolean consoleMode = true;
        private String from = "noreply@example.com";
    }

    @Data
    public static class Code {
        /** 验证码位数 */
        private int length = 6;
        /** 有效期分钟 */
        private int expireMinutes = 10;
        /** 同一邮箱发送间隔秒 */
        private int sendIntervalSeconds = 60;
        /** 滑动窗口内最大校验失败次数 */
        private int maxVerifyFails = 10;
        /** 失败计数窗口分钟 */
        private int verifyFailWindowMinutes = 15;
    }

    @Data
    public static class Admin {
        /** 是否在启动时自动创建超管（仅当库中尚无 SUPER_ADMIN 时） */
        private boolean seedEnabled = true;
        private String email = "admin@okx-bot.local";
        private String password = "Admin@123456";
        private String nickname = "超级管理员";
    }
}
