package com.dwcode.okxbot.blog.adapter;

import com.dwcode.okxbot.blog.port.HaloAttachment;
import com.dwcode.okxbot.blog.port.HaloPostTerms;
import com.dwcode.okxbot.blog.port.HaloPublishCommand;
import com.dwcode.okxbot.blog.port.HaloPublishPort;
import com.dwcode.okxbot.blog.port.HaloPublishResult;
import com.dwcode.okxbot.blog.port.HaloTerm;
import com.dwcode.okxbot.common.exception.BusinessException;

import java.util.List;

/**
 * 未配置 PAT 时的空实现。
 */
public class DisabledHaloPublishAdapter implements HaloPublishPort {

    public static final String MESSAGE = "博客未配置：在 deploy/env/app.env 填写 HALO_PAT 并设置 HALO_ENABLED=true 后重启";

    @Override
    public HaloPublishResult publish(HaloPublishCommand command) {
        throw new BusinessException(503, MESSAGE);
    }

    @Override
    public List<HaloTerm> listCategories() {
        throw new BusinessException(503, MESSAGE);
    }

    @Override
    public List<HaloTerm> listTags() {
        throw new BusinessException(503, MESSAGE);
    }

    @Override
    public HaloPostTerms getPostTerms(String postName) {
        throw new BusinessException(503, MESSAGE);
    }

    @Override
    public HaloAttachment upload(byte[] bytes, String filename, String contentType) {
        throw new BusinessException(503, MESSAGE);
    }
}
