package com.dwcode.okxbot.blog.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HaloBindingResponse {

    private boolean bound;

    /** platform | personal */
    private String target;

    private String siteUrl;

    private String publicUrl;

    private String haloUsername;

    private String tokenMasked;

    private String hint;
}
