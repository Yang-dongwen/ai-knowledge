package com.dwcode.okxbot.article.security;

import com.dwcode.okxbot.article.config.ArticleProperties;
import com.dwcode.okxbot.article.enums.ArticleErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 设计文档 §14.2 单测表。
 */
class UrlSafetyGuardTest {

    private ArticleProperties props;
    private UrlSafetyGuard guard;

    @BeforeEach
    void setUp() {
        props = new ArticleProperties();
        guard = new UrlSafetyGuard(props);
    }

    @Test
    void blockLoopbackLiteral() {
        ArticleSafetyException ex = assertThrows(ArticleSafetyException.class,
                () -> guard.assertSafeUrl("http://127.0.0.1/"));
        assertEquals(ArticleErrorCode.SSRF_BLOCKED, ex.getErrorCode());
    }

    @Test
    void blockPrivate10() {
        ArticleSafetyException ex = assertThrows(ArticleSafetyException.class,
                () -> guard.assertSafeUrl("http://10.0.0.1/"));
        assertEquals(ArticleErrorCode.SSRF_BLOCKED, ex.getErrorCode());
    }

    @Test
    void blockMetadata() {
        ArticleSafetyException ex = assertThrows(ArticleSafetyException.class,
                () -> guard.assertSafeUrl("http://169.254.169.254/latest/meta-data/"));
        assertEquals(ArticleErrorCode.SSRF_BLOCKED, ex.getErrorCode());
    }

    @Test
    void blockIpv6Loopback() {
        ArticleSafetyException ex = assertThrows(ArticleSafetyException.class,
                () -> guard.assertSafeUrl("http://[::1]/"));
        assertEquals(ArticleErrorCode.SSRF_BLOCKED, ex.getErrorCode());
    }

    @Test
    void blockDnsRebindingToLoopback() {
        DnsResolver mockDns = host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")};
        UrlSafetyGuard g = new UrlSafetyGuard(props, mockDns);
        ArticleSafetyException ex = assertThrows(ArticleSafetyException.class,
                () -> g.assertSafeUrl("http://evil.example/path"));
        assertEquals(ArticleErrorCode.SSRF_BLOCKED, ex.getErrorCode());
    }

    @Test
    void rejectFileScheme() {
        ArticleSafetyException ex = assertThrows(ArticleSafetyException.class,
                () -> guard.assertSafeUrl("file:///etc/passwd"));
        assertEquals(ArticleErrorCode.INVALID_URL, ex.getErrorCode());
    }

    @Test
    void rejectUserInfo() {
        DnsResolver mockDns = host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")};
        UrlSafetyGuard g = new UrlSafetyGuard(props, mockDns);
        ArticleSafetyException ex = assertThrows(ArticleSafetyException.class,
                () -> g.assertSafeUrl("http://user:pass@example.com/"));
        assertEquals(ArticleErrorCode.INVALID_URL, ex.getErrorCode());
    }

    @Test
    void allowPublicWithMockDns() throws Exception {
        DnsResolver mockDns = host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")};
        UrlSafetyGuard g = new UrlSafetyGuard(props, mockDns);
        SafeUrl safe = g.assertSafeUrl("https://news.example.com/a/b");
        assertEquals("news.example.com", safe.getHost());
        assertEquals("https", safe.getScheme());
        assertEquals(443, safe.getPort());
        assertFalse(safe.getPinnedAddresses().isEmpty());
        assertEquals("8.8.8.8", safe.getPinnedAddresses().get(0).getHostAddress());
    }

    @Test
    void redirectChainToIntranetBlocked() throws Exception {
        DnsResolver publicDns = host -> {
            if ("public.example".equalsIgnoreCase(host)) {
                return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
            }
            // 内网字面量由 assertSafeUri 直接解析
            return InetAddress.getAllByName(host);
        };
        UrlSafetyGuard g = new UrlSafetyGuard(props, publicDns);
        SafeUrl base = g.assertSafeUrl("https://public.example/start");
        ArticleSafetyException ex = assertThrows(ArticleSafetyException.class,
                () -> g.assertSafeRedirect(base, "http://127.0.0.1/secret"));
        assertEquals(ArticleErrorCode.SSRF_BLOCKED, ex.getErrorCode());
    }

    @Test
    void redirectToPrivateIpBlocked() throws Exception {
        DnsResolver publicDns = host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")};
        UrlSafetyGuard g = new UrlSafetyGuard(props, publicDns);
        SafeUrl base = g.assertSafeUrl("https://public.example/start");
        ArticleSafetyException ex = assertThrows(ArticleSafetyException.class,
                () -> g.assertSafeRedirect(base, "http://10.1.2.3/x"));
        assertEquals(ArticleErrorCode.SSRF_BLOCKED, ex.getErrorCode());
    }

    @Test
    void countingStreamPayloadTooLarge() {
        byte[] data = new byte[100];
        CountingInputStream in = new CountingInputStream(new ByteArrayInputStream(data), 50);
        ArticleSafetyException ex = assertThrows(ArticleSafetyException.class, () -> {
            //noinspection ResultOfMethodCallIgnored
            in.readAllBytes();
        });
        assertEquals(ArticleErrorCode.PAYLOAD_TOO_LARGE, ex.getErrorCode());
    }

    @Test
    void emptyUrlInvalid() {
        ArticleSafetyException ex = assertThrows(ArticleSafetyException.class,
                () -> guard.assertSafeUrl("  "));
        assertEquals(ArticleErrorCode.INVALID_URL, ex.getErrorCode());
    }

    @Test
    void pinnedDnsRejectsOtherHostname() throws Exception {
        DnsResolver mockDns = host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")};
        UrlSafetyGuard g = new UrlSafetyGuard(props, mockDns);
        SafeUrl safe = g.assertSafeUrl("https://news.example.com/");
        okhttp3.Dns pinned = g.createPinnedDns(safe);
        assertThrows(Exception.class, () -> pinned.lookup("other.example.com"));
        assertEquals(1, pinned.lookup("news.example.com").size());
    }
}
