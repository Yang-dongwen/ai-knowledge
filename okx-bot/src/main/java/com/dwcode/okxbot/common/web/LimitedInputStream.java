package com.dwcode.okxbot.common.web;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 最多读取 {@code limit} 字节后 EOF（用于 HTTP Range 响应体）。
 */
public final class LimitedInputStream extends FilterInputStream {

    private long remaining;

    public LimitedInputStream(InputStream in, long limit) {
        super(in);
        this.remaining = Math.max(0, limit);
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int b = super.read();
        if (b >= 0) {
            remaining--;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int toRead = (int) Math.min(len, remaining);
        int n = super.read(b, off, toRead);
        if (n > 0) {
            remaining -= n;
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = super.skip(Math.min(n, remaining));
        remaining -= skipped;
        return skipped;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min(super.available(), remaining);
    }
}
