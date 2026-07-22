package com.dwcode.okxbot.article.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 可注入 DNS，便于单测 mock rebinding。
 */
@FunctionalInterface
public interface DnsResolver {

    /**
     * 解析 host 全部 A/AAAA；字面量 IP 应返回单元素。
     */
    InetAddress[] resolve(String host) throws UnknownHostException;
}
