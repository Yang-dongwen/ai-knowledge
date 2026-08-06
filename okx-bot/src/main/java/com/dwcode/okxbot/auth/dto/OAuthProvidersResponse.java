package com.dwcode.okxbot.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class OAuthProvidersResponse {
    /** 已启用的 provider 小写 id：google / github */
    private List<String> providers;
    /** 是否 mock 模式（前端可提示） */
    private boolean mock;
}
