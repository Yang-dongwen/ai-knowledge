package com.dwcode.okxbot.imggen.adapter;

import com.dwcode.okxbot.imggen.adapter.flux.NvidiaFluxImageAdapter;
import com.dwcode.okxbot.imggen.adapter.mock.MockImageGenAdapter;
import com.dwcode.okxbot.imggen.adapter.qwen.NvidiaQwenImageAdapter;
import com.dwcode.okxbot.imggen.port.ImageGenCommand;
import com.dwcode.okxbot.imggen.port.ImageGenPort;
import com.dwcode.okxbot.imggen.port.ImageGenResult;
import com.dwcode.okxbot.imggen.util.ImageProtocol;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 按协议路由到 FLUX / Qwen / Mock。
 */
@Slf4j
@RequiredArgsConstructor
public class CompositeImageGenPort implements ImageGenPort {

    private final NvidiaFluxImageAdapter fluxAdapter;
    private final NvidiaQwenImageAdapter qwenAdapter;
    private final MockImageGenAdapter mockAdapter;

    @Override
    public ImageGenResult generate(ImageGenCommand cmd) throws Exception {
        String protocol = ImageProtocol.resolve(cmd.getProtocol(), cmd.getModelId(), cmd.getInvokeUrl());
        log.info("ImageGen 路由: protocol={} model={} url={}",
                protocol, cmd.getModelId(), cmd.getInvokeUrl());
        return switch (protocol) {
            case ImageProtocol.MOCK -> mockAdapter.generate(cmd);
            case ImageProtocol.NVIDIA_QWEN,
                 ImageProtocol.NVIDIA_OPENAI_IMAGES,
                 ImageProtocol.NVIDIA_QWEN_INFER -> qwenAdapter.generate(cmd);
            default -> fluxAdapter.generate(cmd);
        };
    }
}
