package com.dwcode.okxbot.article.security;

import java.net.InetAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 已通过 SSRF 校验并钉扎 DNS 的 URL。
 */
public final class SafeUrl {

    private final URI uri;
    private final String host;
    private final String scheme;
    private final int port;
    private final List<InetAddress> pinnedAddresses;

    public SafeUrl(URI uri, String host, String scheme, int port, List<InetAddress> pinnedAddresses) {
        this.uri = Objects.requireNonNull(uri, "uri");
        this.host = Objects.requireNonNull(host, "host");
        this.scheme = Objects.requireNonNull(scheme, "scheme");
        this.port = port;
        this.pinnedAddresses = Collections.unmodifiableList(
                List.copyOf(Objects.requireNonNull(pinnedAddresses, "pinnedAddresses")));
        if (this.pinnedAddresses.isEmpty()) {
            throw new IllegalArgumentException("pinnedAddresses 不能为空");
        }
    }

    public URI getUri() {
        return uri;
    }

    public String getHost() {
        return host;
    }

    public String getScheme() {
        return scheme;
    }

    /**
     * 显式端口；未指定时 http=80 / https=443。
     */
    public int getPort() {
        return port;
    }

    public List<InetAddress> getPinnedAddresses() {
        return pinnedAddresses;
    }

    /**
     * 用于连接的 URI 字符串（保留原始 host，不把 IP 写进 authority，由 Dns 钉扎）。
     */
    public String getUrlString() {
        return uri.toString();
    }
}
