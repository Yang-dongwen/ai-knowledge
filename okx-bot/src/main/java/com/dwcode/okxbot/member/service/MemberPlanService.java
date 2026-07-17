package com.dwcode.okxbot.member.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.member.dto.MemberPlanResponse;
import com.dwcode.okxbot.member.entity.MemberPlanEntity;
import com.dwcode.okxbot.member.mapper.MemberPlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MemberPlanService {

    private final MemberPlanMapper memberPlanMapper;

    public List<MemberPlanResponse> listOnSale() {
        List<MemberPlanEntity> list = memberPlanMapper.selectList(
                new LambdaQueryWrapper<MemberPlanEntity>()
                        .eq(MemberPlanEntity::getStatus, 1)
                        .orderByAsc(MemberPlanEntity::getSortOrder)
                        .orderByAsc(MemberPlanEntity::getId)
        );
        return list.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public MemberPlanEntity requireOnSale(Long planId) {
        MemberPlanEntity plan = memberPlanMapper.selectById(planId);
        if (plan == null || plan.getStatus() == null || plan.getStatus() != 1) {
            throw new BusinessException(404, "套餐不存在或已下架");
        }
        if (plan.getPriceCents() == null || plan.getPriceCents() <= 0) {
            throw new BusinessException(400, "套餐价格无效");
        }
        if (plan.getDurationDays() == null || plan.getDurationDays() <= 0) {
            throw new BusinessException(400, "套餐时长无效");
        }
        return plan;
    }

    public MemberPlanResponse toResponse(MemberPlanEntity e) {
        return MemberPlanResponse.builder()
                .id(String.valueOf(e.getId()))
                .code(e.getCode())
                .name(e.getName())
                .description(e.getDescription())
                .durationDays(e.getDurationDays())
                .priceCents(e.getPriceCents())
                .priceYuan(centsToYuan(e.getPriceCents()))
                .originalPriceCents(e.getOriginalPriceCents())
                .originalPriceYuan(centsToYuan(e.getOriginalPriceCents()))
                .currency(e.getCurrency())
                .sortOrder(e.getSortOrder())
                .build();
    }

    public static String centsToYuan(Integer cents) {
        if (cents == null) {
            return null;
        }
        return BigDecimal.valueOf(cents)
                .movePointLeft(2)
                .setScale(2, RoundingMode.UNNECESSARY)
                .toPlainString();
    }
}
