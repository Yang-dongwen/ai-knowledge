package com.dwcode.okxbot.member.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.member.entity.MemberPlanEntity;
import com.dwcode.okxbot.member.mapper.MemberPlanMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 启动时按 code 幂等种子默认套餐（月 29 / 季 79 / 年 199）。
 */
@Slf4j
@Component
@Order(110)
@RequiredArgsConstructor
public class MemberPlanInitializer implements ApplicationRunner {

    private final MemberPlanMapper memberPlanMapper;

    private record Seed(String code, String name, String desc, int days, int cents, int original, int sort) {
    }

    private static final List<Seed> SEEDS = List.of(
            new Seed("month", "月卡", "30天会员", 30, 2900, 3900, 10),
            new Seed("quarter", "季卡", "90天会员", 90, 7900, 9900, 20),
            new Seed("year", "年卡", "365天会员", 365, 19900, 29900, 30)
    );

    @Override
    public void run(ApplicationArguments args) {
        try {
            LocalDateTime now = LocalDateTime.now();
            for (Seed s : SEEDS) {
                MemberPlanEntity existing = memberPlanMapper.selectOne(
                        new LambdaQueryWrapper<MemberPlanEntity>().eq(MemberPlanEntity::getCode, s.code())
                );
                if (existing != null) {
                    continue;
                }
                MemberPlanEntity e = new MemberPlanEntity();
                e.setCode(s.code());
                e.setName(s.name());
                e.setDescription(s.desc());
                e.setDurationDays(s.days());
                e.setPriceCents(s.cents());
                e.setOriginalPriceCents(s.original());
                e.setCurrency("CNY");
                e.setStatus(1);
                e.setSortOrder(s.sort());
                e.setCreatedAt(now);
                e.setUpdatedAt(now);
                memberPlanMapper.insert(e);
                log.info("种子会员套餐: code={}, priceCents={}", s.code(), s.cents());
            }
        } catch (Exception ex) {
            log.error("种子会员套餐失败（请确认 member_plan 表已创建）: {}", ex.getMessage());
        }
    }
}
