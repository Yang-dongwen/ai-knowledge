package com.dwcode.okxbot.pay.channel.stripe;

import com.dwcode.okxbot.auth.config.AuthProperties;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.pay.channel.NotifyParseResult;
import com.dwcode.okxbot.pay.config.PayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class StripePaymentChannelParseTest {

    private static final String WHSEC = "whsec_test_sandbox_secret";

    @Test
    void sandboxRejectsLiveKeys() {
        PayProperties.Stripe s = new PayProperties.Stripe();
        s.setSandbox(true);
        s.setPublishableKey("pk_live_abc");
        s.setSecretKey("sk_test_abc");
        assertThrows(BusinessException.class, () -> StripeClientFactory.validateKeyMode(s));
    }

    @Test
    void sandboxAcceptsTestKeys() {
        PayProperties.Stripe s = new PayProperties.Stripe();
        s.setSandbox(true);
        s.setPublishableKey("pk_test_abc");
        s.setSecretKey("sk_test_abc");
        assertDoesNotThrow(() -> StripeClientFactory.validateKeyMode(s));
    }

    @Test
    void paidCheckoutSessionWebhook() throws Exception {
        String payload = """
                {"id":"evt_test","object":"event","type":"checkout.session.completed",\
                "data":{"object":{"id":"cs_test_1","object":"checkout.session",\
                "client_reference_id":"P20240910001","payment_status":"paid",\
                "amount_total":2900,"payment_intent":"pi_test_1",\
                "metadata":{"order_no":"P20240910001"}}}}
                """;
        StripePaymentChannel ch = channel(WHSEC);
        HttpHeaders headers = new HttpHeaders();
        headers.add("Stripe-Signature", sign(payload, WHSEC, Instant.now().getEpochSecond()));
        NotifyParseResult r = ch.parseAndVerifyNotify(headers, payload);
        assertTrue(r.isSignatureValid());
        assertTrue(r.isPaid());
        assertEquals("P20240910001", r.getOrderNo());
        assertEquals("pi_test_1", r.getTradeNo());
        assertEquals(2900L, r.getAmountCents());
    }

    @Test
    void rejectBadSignature() {
        String payload = "{\"type\":\"checkout.session.completed\",\"data\":{\"object\":{}}}";
        StripePaymentChannel ch = channel(WHSEC);
        HttpHeaders headers = new HttpHeaders();
        headers.add("Stripe-Signature", "t=1,v1=deadbeef");
        NotifyParseResult r = ch.parseAndVerifyNotify(headers, payload);
        assertFalse(r.isSignatureValid());
        assertFalse(r.isPaid());
    }

    private static StripePaymentChannel channel(String webhookSecret) {
        PayProperties props = new PayProperties();
        props.getStripe().setEnabled(true);
        props.getStripe().setSandbox(true);
        props.getStripe().setWebhookSecret(webhookSecret);
        props.getStripe().setPublishableKey("pk_test_x");
        props.getStripe().setSecretKey("sk_test_x");
        return new StripePaymentChannel(props, new AuthProperties(), new StripeClientFactory(props), new ObjectMapper());
    }

    static String sign(String payload, String secret, long timestamp) throws Exception {
        String signed = timestamp + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String hex = HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
        return "t=" + timestamp + ",v1=" + hex;
    }
}
