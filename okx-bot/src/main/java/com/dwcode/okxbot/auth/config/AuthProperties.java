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
    /** PC 端 Google / GitHub OAuth */
    private OAuth oauth = new OAuth();
    /** 登录暴力破解限流 */
    private LoginLimit loginLimit = new LoginLimit();
    /** CORS 允许的前端 Origin；空则开发友好默认（localhost） */
    private Cors cors = new Cors();

    @Data
    public static class Jwt {
        /** HS256 密钥，生产环境务必使用足够长的随机串 */
        private String secret = "change-me-to-a-very-long-random-secret-key-for-jwt-hs256-okx-bot";
        /** 访问令牌有效期（秒），默认 2 小时 */
        private long expireSeconds = 7200;
        private String issuer = "okx-bot";
    }

    @Data
    public static class LoginLimit {
        private boolean enabled = true;
        /** 滑动窗口秒数 */
        private int windowSeconds = 900;
        /** 窗口内最大失败次数 */
        private int maxFails = 8;
        /** 触发后锁定秒数 */
        private int lockSeconds = 600;
    }

    @Data
    public static class Cors {
        /**
         * 允许的 Origin 列表。生产请显式配置；空列表时使用下方默认本地源。
         * 支持精确 URL，如 https://dwcode.cloud
         */
        private java.util.List<String> allowedOrigins = new java.util.ArrayList<>();
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
             * false 且 {@link #mock}=true 时才走 mock openid。
             */
            private boolean enabled = false;
            /**
             * 显式允许 mock（仅本地）。enabled=true 时永远不会 mock。
             */
            private boolean mock = true;
            /** 小程序 AppID */
            private String appId = "";
            /** 小程序 AppSecret */
            private String appSecret = "";
        }
    }

    @Data
    public static class OAuth {
        /**
         * true：不访问 Google/GitHub，authorize 直接签发 mock 用户 ticket（本地/CI）。
         */
        private boolean mock = false;
        /** 前端 SPA 根地址，回调成功后 302 到 {frontend}/oauth/callback */
        private String frontendBaseUrl = "http://localhost:3000";
        /** 后端对外根地址，拼 JustAuth redirect_uri */
        private String callbackBaseUrl = "http://localhost:8080";
        /** one-time ticket / state 有效期（秒） */
        private long ticketTtlSeconds = 60;
        /**
         * 登录成功后允许的前端路径白名单（前缀匹配）。
         * 非空时强制校验；空列表时仅校验「相对路径且不以 // 开头」。
         */
        private java.util.List<String> allowedRedirectPaths = new java.util.ArrayList<>();
        /**
         * true：邮箱已存在时自动绑定 OAuth（不安全，默认关闭）。
         * false：邮箱已注册则拒绝自动绑定，需用户先密码登录。
         */
        private boolean autoLinkByEmail = false;
        /**
         * 访问 GitHub/Google 的 HTTP 代理（本机 Clash 常见 127.0.0.1:7897）。
         * Java 不会自动走系统/浏览器代理，本地连不上 api.github.com 时必须配置。
         */
        private String proxyHost = "";
        private int proxyPort = 0;
        /** HTTP 或 SOCKS */
        private String proxyType = "HTTP";
        private Provider google = new Provider();
        private Provider github = new Provider();

        @Data
        public static class Provider {
            private boolean enabled = false;
            private String clientId = "";
            private String clientSecret = "";
        }
    }
}
