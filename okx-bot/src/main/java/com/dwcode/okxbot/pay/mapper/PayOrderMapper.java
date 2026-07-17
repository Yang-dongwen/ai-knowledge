package com.dwcode.okxbot.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dwcode.okxbot.pay.entity.PayOrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PayOrderMapper extends BaseMapper<PayOrderEntity> {

    @Update("""
            UPDATE pay_order
            SET status = 'SUCCESS',
                trade_no = #{tradeNo},
                paid_at = NOW(3),
                updated_at = NOW(3),
                version = version + 1
            WHERE order_no = #{orderNo}
              AND status IN ('CREATED', 'PAYING', 'CLOSED')
            """)
    int casToSuccess(@Param("orderNo") String orderNo, @Param("tradeNo") String tradeNo);

    @Update("""
            UPDATE pay_order
            SET fulfilled = 1,
                updated_at = NOW(3),
                version = version + 1
            WHERE order_no = #{orderNo}
              AND fulfilled = 0
            """)
    int markFulfilledIfNot(@Param("orderNo") String orderNo);

    @Update("""
            UPDATE pay_order
            SET status = 'CLOSED',
                closed_at = NOW(3),
                close_reason = #{reason},
                updated_at = NOW(3),
                version = version + 1
            WHERE order_no = #{orderNo}
              AND status IN ('CREATED', 'PAYING')
            """)
    int casToClosed(@Param("orderNo") String orderNo, @Param("reason") String reason);

    @Update("""
            UPDATE pay_order
            SET status = 'PAYING',
                code_url = #{codeUrl},
                pay_url = #{payUrl},
                prepay_id = #{prepayId},
                channel_extra_json = #{extra},
                updated_at = NOW(3),
                version = version + 1
            WHERE order_no = #{orderNo}
              AND status = 'CREATED'
            """)
    int casToPaying(@Param("orderNo") String orderNo,
                    @Param("codeUrl") String codeUrl,
                    @Param("payUrl") String payUrl,
                    @Param("prepayId") String prepayId,
                    @Param("extra") String extra);

    @Update("""
            UPDATE pay_order
            SET status = 'FAILED',
                close_reason = #{reason},
                updated_at = NOW(3),
                version = version + 1
            WHERE order_no = #{orderNo}
              AND status = 'CREATED'
            """)
    int casToFailed(@Param("orderNo") String orderNo, @Param("reason") String reason);
}
