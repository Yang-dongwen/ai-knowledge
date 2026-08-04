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
    /** 微信小程序登录 */
    private Wechat wechat = new Wechat();

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
         * 发件提供方：console | agentmail | smtp。
         * <ul>
         *   <li>console — 验证码打印到日志（本地默认）</li>
         *   <li>agentmail — AgentMail HTTP API（推荐生产）</li>
         *   <li>smtp — spring.mail（如 QQ 邮箱 SMTP）</li>
         * </ul>
         * 若 {@link #consoleMode} 为 true，则强制 console（兼容旧配置）。
         */
        private String provider = "console";
        /**
         * true：不真实发信，验证码打印到日志（本地开发）。
         * false：按 {@link #provider} 真实发信。
         */
        private boolean consoleMode = true;
        private String from = "noreply@example.com";
        /** AgentMail 配置（provider=agentmail 时必填） */
        private AgentMail agentmail = new AgentMail();

        @Data
        public static class AgentMail {
            /** API Key，环境变量 AGENTMAIL_API_KEY */
            private String apiKey = "";
            /** 发件 inbox_id，环境变量 AGENTMAIL_INBOX_ID */
            private String inboxId = "";
            /** API 根地址，默认 https://api.agentmail.to */
            private String baseUrl = "https://api.agentmail.to";
            /**
             * 可选 HTTP/SOCKS 代理（本机 Clash/VPN 常见 127.0.0.1:7890/7897）。
             * Java/OkHttp 不会自动走系统 VPN，需显式配置。
             */
            private String proxyHost = "";
            /** 代理端口，0 表示不使用 */
            private int proxyPort = 0;
            /** HTTP 或 SOCKS（Clash 混合端口一般用 HTTP） */
            private String proxyType = "HTTP";
        }
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

    @Data
    public static class Wechat {
        private Mini mini = new Mini();

        @Data
        public static class Mini {
            /**
             * 是否启用真实 jscode2session。
             * false 时进入 mock：code 本身（或 mock:xxx）作为 openid，便于本地/touristappid 联调。
             */
            private boolean enabled = false;
            /** 小程序 AppID */
            private String appId = "";
            /** 小程序 AppSecret */
            private String appSecret = "";
        }
    }
}
