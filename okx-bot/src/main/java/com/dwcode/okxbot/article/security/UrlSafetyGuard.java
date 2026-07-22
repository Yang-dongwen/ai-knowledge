package com.dwcode.okxbot.article.security;

import com.dwcode.okxbot.article.config.ArticleProperties;
import com.dwcode.okxbot.article.enums.ArticleErrorCode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Dns;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SSRF 防护：scheme/host 校验 + DNS 解析校验 + 连接钉扎（必选）。
 *
 * <p>算法见设计文档 §14.2。
 * <p>注意：存在双构造器时必须 {@link Autowired} 标明 Spring 用的主构造器，
 * 否则会误选「两参数」构造器（{@link DnsResolver} 非 Bean）或报无默认构造器。
 */
@Slf4j
@Component
public class UrlSafetyGuard {

    private final ArticleProperties articleProperties;
    private final DnsResolver dnsResolver;

    /**
     * Spring 注入入口：使用系统 DNS。
     */
    @Autowired
    public UrlSafetyGuard(ArticleProperties articleProperties) {
        this(articleProperties, DefaultDnsResolver.INSTANCE);
    }

    /**
     * 单测可注入 mock {@link DnsResolver}（非 Spring 管理）。
     */
    public UrlSafetyGuard(ArticleProperties articleProperties, DnsResolver dnsResolver) {
        this.articleProperties = Objects.requireNonNull(articleProperties, "articleProperties");
        this.dnsResolver = Objects.requireNonNull(dnsResolver, "dnsResolver");
    }

    /**
     * 解析并校验 URL；成功返回带 pinnedAddresses 的 {@link SafeUrl}。
     */
    public SafeUrl assertSafeUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ArticleSafetyException(ArticleErrorCode.INVALID_URL, "URL 不能为空");
        }
        String trimmed = raw.trim();
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new ArticleSafetyException(ArticleErrorCode.INVALID_URL, "无法解析 URL: " + e.getMessage());
        }
        return assertSafeUri(uri);
    }

    /**
     * 对已解析 URI 做安全校验（redirect 每跳调用）。
     */
    public SafeUrl assertSafeUri(URI uri) {
        if (uri == null) {
            throw new ArticleSafetyException(ArticleErrorCode.INVALID_URL, "URI 不能为空");
        }

        String scheme = uri.getScheme();
        if (scheme == null || scheme.isBlank()) {
            throw new ArticleSafetyException(ArticleErrorCode.INVALID_URL, "URL 缺少 scheme");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        List<String> allowed = articleProperties.getSafety().getAllowedSchemes();
        if (allowed == null || allowed.isEmpty()) {
            allowed = List.of("http", "https");
        }
        Set<String> allowedSet = allowed.stream()
                .filter(Objects::nonNull)
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (!allowedSet.contains(scheme)) {
            throw new ArticleSafetyException(ArticleErrorCode.INVALID_URL,
                    "不允许的 URL scheme: " + scheme + "（仅允许 " + allowedSet + "）");
        }

        // 拒绝 userInfo（user:pass@host）
        if (uri.getUserInfo() != null && !uri.getUserInfo().isEmpty()) {
            throw new ArticleSafetyException(ArticleErrorCode.INVALID_URL,
                    "URL 不允许包含 userInfo（user:pass@）");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            // 部分 IPv6 字面量在异常写法下 getHost 为 null
            throw new ArticleSafetyException(ArticleErrorCode.INVALID_URL, "URL 缺少有效 host");
        }
        // 去掉 IPv6 字面量外层括号（URI.getHost 通常已无括号）
        host = host.trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }

        // host 白名单（非空时强制）
        List<String> allowlist = articleProperties.getSafety().getHostAllowlist();
        if (allowlist != null && !allowlist.isEmpty()) {
            String hostLower = host.toLowerCase(Locale.ROOT);
            boolean ok = allowlist.stream()
                    .filter(Objects::nonNull)
                    .map(s -> s.trim().toLowerCase(Locale.ROOT))
                    .anyMatch(h -> h.equals(hostLower) || hostLower.endsWith("." + h));
            if (!ok) {
                throw new ArticleSafetyException(ArticleErrorCode.SSRF_BLOCKED,
                        "host 不在 allowlist: " + host);
            }
        }

        // 字面量 IP：直接钉扎该地址，禁止经 DNS 被 mock/劫持成「看起来合法」的公网 IP
        InetAddress[] resolved;
        try {
            if (isLiteralIp(host)) {
                resolved = new InetAddress[]{InetAddress.getByName(host)};
            } else {
                resolved = dnsResolver.resolve(host);
            }
        } catch (UnknownHostException e) {
            throw new ArticleSafetyException(ArticleErrorCode.INVALID_URL,
                    "DNS 解析失败: " + host + " — " + e.getMessage());
        }
        if (resolved == null || resolved.length == 0) {
            throw new ArticleSafetyException(ArticleErrorCode.INVALID_URL, "DNS 无记录: " + host);
        }

        List<InetAddress> pinned = new ArrayList<>();
        for (InetAddress addr : resolved) {
            if (addr == null) {
                continue;
            }
            if (isBlockedAddress(addr)) {
                throw new ArticleSafetyException(ArticleErrorCode.SSRF_BLOCKED,
                        "拒绝访问内网/保留地址: " + host + " → " + addr.getHostAddress());
            }
            pinned.add(addr);
        }
        if (pinned.isEmpty()) {
            throw new ArticleSafetyException(ArticleErrorCode.INVALID_URL, "无有效解析地址: " + host);
        }

        int port = uri.getPort();
        if (port < 0) {
            port = "https".equals(scheme) ? 443 : 80;
        }

        return new SafeUrl(uri, host, scheme, port, pinned);
    }

    /**
     * redirect Location → 绝对 URI 再校验（相对路径相对 base 解析）。
     */
    public SafeUrl assertSafeRedirect(SafeUrl base, String locationHeader) {
        if (locationHeader == null || locationHeader.isBlank()) {
            throw new ArticleSafetyException(ArticleErrorCode.INVALID_URL, "redirect Location 为空");
        }
        String loc = locationHeader.trim();
        URI next;
        try {
            URI baseUri = base.getUri();
            next = baseUri.resolve(loc);
        } catch (Exception e) {
            throw new ArticleSafetyException(ArticleErrorCode.INVALID_URL,
                    "无法解析 redirect Location: " + loc);
        }
        // resolve 可能保留 fragment；校验用规范化
        try {
            next = new URI(next.getScheme(), next.getAuthority(), next.getPath(),
                    next.getQuery(), null);
        } catch (URISyntaxException e) {
            throw new ArticleSafetyException(ArticleErrorCode.INVALID_URL,
                    "redirect Location 非法: " + e.getMessage());
        }
        return assertSafeUri(next);
    }

    /**
     * OkHttp {@link Dns}：仅返回已校验的 pinned 地址，禁止连接时二次自由解析。
     */
    public Dns createPinnedDns(SafeUrl safeUrl) {
        List<InetAddress> pinned = safeUrl.getPinnedAddresses();
        return hostname -> {
            // OkHttp 传入的 hostname 可能与校验 host 大小写不同
            if (hostname == null) {
                throw new UnknownHostException("hostname null");
            }
            String h = hostname.trim();
            if (h.startsWith("[") && h.endsWith("]")) {
                h = h.substring(1, h.length() - 1);
            }
            if (!h.equalsIgnoreCase(safeUrl.getHost())) {
                // 不应发生：Client 应按 SafeUrl 的 host 请求
                log.warn("PinnedDns hostname 与 SafeUrl 不一致: req={}, pinnedHost={}",
                        hostname, safeUrl.getHost());
                throw new UnknownHostException(
                        "DNS pin 拒绝非钉扎 host: " + hostname + "（期望 " + safeUrl.getHost() + "）");
            }
            return pinned;
        };
    }

    /**
     * host 是否为 IPv4/IPv6 字面量（非域名）。
     */
    static boolean isLiteralIp(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        // IPv4
        if (host.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '.')) {
            return host.indexOf('.') > 0;
        }
        // IPv6 含冒号
        return host.indexOf(':') >= 0;
    }

    /**
     * 判断 IP 是否为禁止出站目标（内网 / loopback / link-local / CGNAT / metadata / ULA 等）。
     */
    public boolean isBlockedAddress(InetAddress addr) {
        if (addr == null) {
            return true;
        }
        boolean blockPrivate = articleProperties.getSafety().isBlockPrivateIp();
        boolean blockMetadata = articleProperties.getSafety().isBlockMetadataIp();
        boolean allowLoopback = articleProperties.getSafety().isAllowLoopback();

        if (addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
            return true;
        }
        if (addr.isLoopbackAddress()) {
            return !allowLoopback;
        }
        if (blockPrivate && (addr.isLinkLocalAddress() || addr.isSiteLocalAddress())) {
            return true;
        }

        byte[] raw = addr.getAddress();
        if (addr instanceof Inet4Address || raw.length == 4) {
            return isBlockedIpv4(raw, blockPrivate, blockMetadata, allowLoopback);
        }
        if (addr instanceof Inet6Address || raw.length == 16) {
            Inet6Address v6 = addr instanceof Inet6Address ? (Inet6Address) addr : null;
            return isBlockedIpv6(raw, v6, blockPrivate, blockMetadata, allowLoopback);
        }
        return true;
    }

    private static boolean isBlockedIpv4(byte[] b, boolean blockPrivate, boolean blockMetadata,
                                         boolean allowLoopback) {
        int a = b[0] & 0xFF;
        int b1 = b[1] & 0xFF;
        int c = b[2] & 0xFF;
        int d = b[3] & 0xFF;

        // 0.0.0.0/8
        if (a == 0) {
            return true;
        }
        // 127.0.0.0/8（部分 JVM 对非 127.0.0.1 的 loopback 判断不一致）
        if (a == 127) {
            return !allowLoopback;
        }
        if (blockPrivate) {
            // 10.0.0.0/8
            if (a == 10) {
                return true;
            }
            // 172.16.0.0/12
            if (a == 172 && b1 >= 16 && b1 <= 31) {
                return true;
            }
            // 192.168.0.0/16
            if (a == 192 && b1 == 168) {
                return true;
            }
            // CGNAT 100.64.0.0/10
            if (a == 100 && b1 >= 64 && b1 <= 127) {
                return true;
            }
            // 169.254.0.0/16 link-local
            if (a == 169 && b1 == 254) {
                return true;
            }
        }
        if (blockMetadata) {
            // 云 metadata 经典地址（已在 link-local 覆盖，显式保留）
            if (a == 169 && b1 == 254 && c == 169 && d == 254) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlockedIpv6(byte[] b, Inet6Address inet6,
                                         boolean blockPrivate, boolean blockMetadata,
                                         boolean allowLoopback) {
        // IPv4-compatible ::x.x.x.x — 检查低 4 字节
        if (inet6 != null && inet6.isIPv4CompatibleAddress()) {
            byte[] v4 = Arrays.copyOfRange(b, 12, 16);
            return isBlockedIpv4(v4, blockPrivate, blockMetadata, allowLoopback);
        }
        // mapped ::ffff:x.x.x.x
        boolean mapped = b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 0
                && b[4] == 0 && b[5] == 0 && b[6] == 0 && b[7] == 0
                && b[8] == 0 && b[9] == 0 && b[10] == (byte) 0xFF && b[11] == (byte) 0xFF;
        if (mapped) {
            byte[] v4 = Arrays.copyOfRange(b, 12, 16);
            return isBlockedIpv4(v4, blockPrivate, blockMetadata, allowLoopback);
        }

        // ::1 already loopback
        // fc00::/7 unique local
        if (blockPrivate) {
            int first = b[0] & 0xFE;
            if (first == 0xFC) {
                return true;
            }
            // fe80::/10 link-local — usually covered by isLinkLocalAddress
            if ((b[0] & 0xFF) == 0xFE && (b[1] & 0xC0) == 0x80) {
                return true;
            }
        }
        if (blockMetadata) {
            // 部分云用 fd00:ec2::254 等；ULA 已拦。保留扩展点。
        }
        return false;
    }
}
