package com.dwcode.okxbot.kb.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 按序打开多个文件，拼成一条流；同时只打开当前分片。
 */
final class SequentialFilesInputStream extends InputStream {

    private final List<Path> files;
    private int index = -1;
    private InputStream current;

    SequentialFilesInputStream(List<Path> files) {
        this.files = List.copyOf(Objects.requireNonNull(files));
    }

    @Override
    public int read() throws IOException {
        byte[] b = new byte[1];
        int n = read(b, 0, 1);
        return n < 0 ? -1 : (b[0] & 0xFF);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        while (true) {
            if (current == null && !openNext()) {
                return -1;
            }
            int n = current.read(b, off, len);
            if (n >= 0) {
                return n;
            }
            current.close();
            current = null;
        }
    }

    private boolean openNext() throws IOException {
        index++;
        if (index >= files.size()) {
            return false;
        }
        current = Files.newInputStream(files.get(index));
        return true;
    }

    @Override
    public void close() throws IOException {
        if (current != null) {
            current.close();
            current = null;
        }
    }
}
