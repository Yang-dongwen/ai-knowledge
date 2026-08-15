package com.dwcode.okxbot.kb.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HaloTermResponse {

    /** Halo metadata.name */
    private String name;

    private String displayName;
}
