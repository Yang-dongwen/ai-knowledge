package com.dwcode.okxbot.pay.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "pay")
public class PayProperties {

    /** 总开关：false 时支付 API 返回 503 */
    private boolean enabled = true;

    /** 回调绝对前缀，如 https://api.example.com */
    private String publicBaseUrl = "http://127.0.0.1:8080";

    private int orderExpireMinutes = 30;
    private int maxOpenOrdersPerUser = 3;

    private String reconcileCron = "0 */1 * * * ?";
    private String closeCron = "0 */1 * * * ?";
    private String fulfillPendingCron = "0 */1 * * * ?";
    private int fulfillPendingGraceSeconds = 30;
    private String memberExpireCron = "0 5 * * * ?";

    /** 开发 Mock 通道；仅 local 等开发 profile 应开启，生产/ec2 必须 false */
    private boolean mockEnabled = false;

    private boolean trustXForwardedFor = false;

    private Alipay alipay = new Alipay();
    private Wechat wechat = new Wechat();
    private Stripe stripe = new Stripe();

    @Data
    public static class Alipay {
        /**
         * 是否启用支付宝通道。无资质/无密钥时请保持 false（默认）。
         * 为 true 时仍须配置 app-id、private-key、alipay-public-key，否则下单失败。
         */
        private boolean enabled = false;
        /** 开放平台应用 APPID */
        private String appId = "";
        /**
         * 应用私钥（PKCS8，可多行；yml 可用 | 块或一行去头尾）。
         * 建议用环境变量 ALIPAY_PRIVATE_KEY，勿提交仓库。
         */
        private String privateKey = "";
        /**
         * 支付宝公钥（不是应用公钥）。
         * 建议环境变量 ALIPAY_PUBLIC_KEY。
         */
        private String alipayPublicKey = "";
        private String signType = "RSA2";
        private String charset = "UTF-8";
        private String format = "json";
        /**
         * 网关：正式 https://openapi.alipay.com/gateway.do
         * 沙箱 https://openapi-sandbox.dl.alipaydev.com/gateway.do
         */
        private String serverUrl = "https://openapi.alipay.com/gateway.do";
        private String notifyPath = "/api/pay/notify/alipay";
        private String returnPath = "/api/pay/return/alipay";
        /** 订单标题前缀 */
        private String subjectPrefix = "会员充值-";
    }

    @Data
    public static class Wechat {
        private boolean enabled = false;
        private String appId;
        private String mchId;
        private String apiV3Key;
        private String merchantSerialNumber;
        private String privateKeyPath;
        private String notifyPath = "/api/pay/notify/wechat";
    }

    /**
     * Stripe Checkout（托管支付页）。
     * <p>沙箱（sandbox=true，默认）：仅接受 pk_test_ / sk_test_。
     * 公钥 publishable-key、私钥 secret-key 用环境变量注入，勿提交仓库。
     * 文档：https://docs.stripe.com/keys 、https://docs.stripe.com/checkout/quickstart
     */
    @Data
    public static class Stripe {
        private boolean enabled = false;
        /**
         * true：沙箱/测试模式，密钥必须为 *_test_；false：正式 live 密钥。
         * Stripe 沙箱与 live 共用 https://api.stripe.com，靠密钥前缀区分。
         */
        private boolean sandbox = true;
        /** 公钥 Publishable key（pk_test_... / pk_live_...），预留给前端 Stripe.js */
        private String publishableKey = "";
        /** 私钥 Secret key（sk_test_... / sk_live_...），服务端创建 Checkout Session */
        private String secretKey = "";
        /**
         * Webhook 签名密钥（whsec_...）。Dashboard 或 {@code stripe listen} 获得。
         * 未配置时异步通知拒收，仍可通过查单补偿。
         */
        private String webhookSecret = "";
        private String notifyPath = "/api/pay/notify/stripe";
        private String returnPath = "/api/pay/return/stripe";
        private String subjectPrefix = "会员充值-";
        /**
         * Checkout 完成后跳回前端的路径（拼在 auth.oauth.frontend-base-url 后）。
         * 空则使用 API return-path 的引导页。
         */
        private String frontendReturnPath = "/member/recharge";
    }
}
