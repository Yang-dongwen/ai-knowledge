package com.dwcode.okxbot.blog.port;

/**
 * 唯一认识 Halo 的出口。未配置时应抛业务异常。
 */
public interface HaloPublishPort {

    HaloPublishResult publish(HaloPublishCommand command);
}
