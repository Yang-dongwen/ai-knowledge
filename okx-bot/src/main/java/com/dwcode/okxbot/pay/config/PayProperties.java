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
        private boolean enabled = false;
        private String appId;
        private String privateKey;
        private String alipayPublicKey;
        private String signType = "RSA2";
        private String serverUrl = "https://openapi.alipay.com/gateway.do";
        private String notifyPath = "/api/pay/notify/alipay";
        private String returnPath = "/api/pay/return/alipay";
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
