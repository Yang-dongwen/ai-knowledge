package com.dwcode.okxbot.member.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MemberPlanResponse {
    private String id;
    private String code;
    private String name;
    private String description;
    private Integer durationDays;
    private Integer priceCents;
    /** 元字符串，如 29.00 */
    private String priceYuan;
    private Integer originalPriceCents;
    private String originalPriceYuan;
    private String currency;
    private Integer sortOrder;
}
