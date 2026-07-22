package com.dwcode.okxbot.article.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 系统 DNS：{@link InetAddress#getAllByName(String)}。
 */
public final class DefaultDnsResolver implements DnsResolver {

    public static final DefaultDnsResolver INSTANCE = new DefaultDnsResolver();

    private DefaultDnsResolver() {
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }
}
