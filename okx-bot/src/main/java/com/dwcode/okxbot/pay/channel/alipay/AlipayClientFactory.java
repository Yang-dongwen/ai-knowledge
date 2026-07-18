package com.dwcode.okxbot.pay.channel.alipay;

import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.pay.config.PayProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 懒加载 AlipayClient。enabled=false 或密钥未配时不创建客户端。
 */
@Component
@RequiredArgsConstructor
public class AlipayClientFactory {

    private final PayProperties payProperties;
    private volatile AlipayClient client;

    public boolean isReady() {
        PayProperties.Alipay a = payProperties.getAlipay();
        return a.isEnabled()
                && StringUtils.hasText(a.getAppId())
                && StringUtils.hasText(a.getPrivateKey())
                && StringUtils.hasText(a.getAlipayPublicKey());
    }

    public void requireReady() {
        if (!payProperties.getAlipay().isEnabled()) {
            throw new BusinessException(400, "支付宝通道未开启（pay.alipay.enabled=false）");
        }
        if (!isReady()) {
            throw new BusinessException(503,
                    "支付宝密钥未配置完整（需 app-id / private-key / alipay-public-key）。无商户资质前请保持 enabled=false 或改用 mock");
        }
    }

    public AlipayClient getClient() {
        requireReady();
        AlipayClient c = client;
        if (c != null) {
            return c;
        }
        synchronized (this) {
            if (client == null) {
                PayProperties.Alipay a = payProperties.getAlipay();
                client = new DefaultAlipayClient(
                        a.getServerUrl(),
                        a.getAppId().trim(),
                        normalizeKey(a.getPrivateKey()),
                        a.getFormat(),
                        a.getCharset(),
                        normalizeKey(a.getAlipayPublicKey()),
                        a.getSignType()
                );
            }
            return client;
        }
    }

    /** 去掉 PEM 头尾与空白，兼容 yml 多行粘贴 */
    public static String normalizeKey(String key) {
        if (key == null) {
            return "";
        }
        return key
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
    }
}
