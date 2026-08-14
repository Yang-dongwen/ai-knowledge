package com.dwcode.okxbot.blog.adapter;

import com.dwcode.okxbot.blog.port.HaloPublishCommand;
import com.dwcode.okxbot.blog.port.HaloPublishPort;
import com.dwcode.okxbot.blog.port.HaloPublishResult;
import com.dwcode.okxbot.common.exception.BusinessException;

/**
 * 未配置 PAT 时的空实现。
 */
public class DisabledHaloPublishAdapter implements HaloPublishPort {

    public static final String MESSAGE = "博客未配置：在 deploy/env/app.env 填写 HALO_PAT 并设置 HALO_ENABLED=true 后重启";

    @Override
    public HaloPublishResult publish(HaloPublishCommand command) {
        throw new BusinessException(503, MESSAGE);
    }
}
