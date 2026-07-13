package com.dwcode.okxbot.imggen.port;

/**
 * 文生图端口。实现：NVIDIA FLUX / Mock。
 */
public interface ImageGenPort {
    ImageGenResult generate(ImageGenCommand cmd) throws Exception;
}
