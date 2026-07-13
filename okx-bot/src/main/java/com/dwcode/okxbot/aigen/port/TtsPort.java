package com.dwcode.okxbot.aigen.port;

public interface TtsPort {
    TtsResult synthesize(TtsCommand command) throws Exception;
}
