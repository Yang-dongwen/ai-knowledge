package com.dwcode.okxbot.strategy.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建/修改策略请求。
 */
@Data
public class CreateStrategyRequest {

    @NotBlank(message = "策略名称不能为空")
    private String strategyName;

    @NotBlank(message = "交易对不能为空")
    private String symbol;

    @NotBlank(message = "K线周期不能为空")
    private String timeframe;

    @NotNull(message = "快线周期不能为空")
    @Min(value = 2, message = "快线周期最小为2")
    private Integer fastPeriod;

    @NotNull(message = "慢线周期不能为空")
    @Min(value = 3, message = "慢线周期最小为3")
    private Integer slowPeriod;

    @NotNull(message = "买入资金比例不能为空")
    @DecimalMin(value = "0.01", message = "买入资金比例最小为0.01")
    @DecimalMax(value = "1.0", message = "买入资金比例最大为1.0")
    private BigDecimal tradeAmountPct;

    @NotNull(message = "止损比例不能为空")
    @DecimalMin(value = "0.001", message = "止损比例最小为0.001")
    private BigDecimal stopLossPct;

    @NotNull(message = "止盈比例不能为空")
    @DecimalMin(value = "0.001", message = "止盈比例最小为0.001")
    private BigDecimal takeProfitPct;

    private String runMode;
}
