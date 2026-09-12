package com.dwcode.okxbot.rag.port;

import java.io.InputStream;

public interface TextExtractPort {

    boolean supports(String kind, String filename, String contentType);

    String extract(InputStream in, String filename, long maxBytes);
}
