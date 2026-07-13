package com.dwcode.okxbot.aigen.port;

public interface VideoRenderPort {
    RenderResult render(RenderCommand command) throws Exception;
}
