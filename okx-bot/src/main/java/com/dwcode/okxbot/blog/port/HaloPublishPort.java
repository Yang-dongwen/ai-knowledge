package com.dwcode.okxbot.blog.port;

import java.util.List;

/**
 * 唯一认识 Halo 的出口。未配置时应抛业务异常。
 */
public interface HaloPublishPort {

    HaloPublishResult publish(HaloPublishCommand command);

    List<HaloTerm> listCategories();

    List<HaloTerm> listTags();

    HaloPostTerms getPostTerms(String postName);

    HaloAttachment upload(byte[] bytes, String filename, String contentType);
}
