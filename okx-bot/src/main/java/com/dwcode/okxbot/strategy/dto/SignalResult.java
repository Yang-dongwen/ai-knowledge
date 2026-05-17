package com.dwcode.okxbot.strategy.dto;

import com.dwcode.okxbot.common.enums.TradeSignalEnum;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 策略信号结果。
 */
@Data
public class SignalResult {

    private TradeSignalEnum signal;
    private BigDecimal closePrice;
    private BigDecimal fastMa;
    private BigDecimal slowMa;
    private Long candleTime;
    private String reason;

    public static SignalResult buy(BigDecimal closePrice, BigDecimal fastMa, BigDecimal slowMa, Long candleTime, String reason) {
        SignalResult result = new SignalResult();
        result.setSignal(TradeSignalEnum.BUY);
        result.setClosePrice(closePrice);
        result.setFastMa(fastMa);
        result.setSlowMa(slowMa);
        result.setCandleTime(candleTime);
        result.setReason(reason);
        return result;
    }

    public static SignalResult sell(BigDecimal closePrice, BigDecimal fastMa, BigDecimal slowMa, Long candleTime, String reason) {
        SignalResult result = new SignalResult();
        result.setSignal(TradeSignalEnum.SELL);
        result.setClosePrice(closePrice);
        result.setFastMa(fastMa);
        result.setSlowMa(slowMa);
        result.setCandleTime(candleTime);
        result.setReason(reason);
        return result;
    }

    public static SignalResult hold(BigDecimal closePrice, BigDecimal fastMa, BigDecimal slowMa, Long candleTime, String reason) {
        SignalResult result = new SignalResult();
        result.setSignal(TradeSignalEnum.HOLD);
        result.setClosePrice(closePrice);
        result.setFastMa(fastMa);
        result.setSlowMa(slowMa);
        result.setCandleTime(candleTime);
        result.setReason(reason);
        return result;
    }

    public static SignalResult hold(String reason) {
        SignalResult result = new SignalResult();
        result.setSignal(TradeSignalEnum.HOLD);
        result.setReason(reason);
        return result;
    }
}
