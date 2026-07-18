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

    /** 开发 Mock 通道；生产 profile 下应为 false */
    private boolean mockEnabled = true;

    private boolean trustXForwardedFor = false;

    private Alipay alipay = new Alipay();
    private Wechat wechat = new Wechat();

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
}
