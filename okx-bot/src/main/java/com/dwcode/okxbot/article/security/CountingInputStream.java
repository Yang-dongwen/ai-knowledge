package com.dwcode.okxbot.article.security;

import com.dwcode.okxbot.article.enums.ArticleErrorCode;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 累计已读字节；超过 {@code maxBytes} 抛 {@link ArticleSafetyException}（PAYLOAD_TOO_LARGE）。
 * <p>字节计数在<strong>解压后</strong>流上使用（调用方保证）。
 */
public final class CountingInputStream extends FilterInputStream {

    private final long maxBytes;
    private long count;

    public CountingInputStream(InputStream in, long maxBytes) {
        super(in);
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes 须 > 0");
        }
        this.maxBytes = maxBytes;
    }

    public long getCount() {
        return count;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            add(1);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            add(n);
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = super.skip(n);
        if (skipped > 0) {
            add(skipped);
        }
        return skipped;
    }

    private void add(long n) {
        count += n;
        if (count > maxBytes) {
            throw new ArticleSafetyException(
                    ArticleErrorCode.PAYLOAD_TOO_LARGE,
                    "响应体超过限制（maxBytes=" + maxBytes + "）");
        }
    }
}
