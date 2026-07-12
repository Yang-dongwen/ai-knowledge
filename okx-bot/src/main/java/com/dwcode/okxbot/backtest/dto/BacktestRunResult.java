package com.dwcode.okxbot.backtest.dto;

import com.dwcode.okxbot.backtest.entity.BacktestEquityCurveEntity;
import com.dwcode.okxbot.backtest.entity.BacktestTradeEntity;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 回测引擎运行结果。
 *
 * 这是 BacktestEngine 的纯输出对象：不含任何数据库主键，
 * 由 BacktestService 负责落库时再补充 taskId。
 */
@Data
public class BacktestRunResult {

    // ---------- 绩效指标 ----------

    /** 期末权益 */
    private BigDecimal finalEquity;

    /** 总收益率 */
    private BigDecimal totalReturn;

    /** 年化收益率 */
    private BigDecimal annualReturn;

    /** 最大回撤 */
    private BigDecimal maxDrawdown;

    /** 夏普比率 */
    private BigDecimal sharpeRatio;

    /** 胜率 */
    private BigDecimal winRate;

    /** 盈亏比(Profit Factor) */
    private BigDecimal profitFactor;

    /** 交易次数(完整买卖回合) */
    private Integer tradeCount;

    /** 最大连续亏损次数 */
    private Integer maxConsecutiveLosses;

    /** 手续费总额 */
    private BigDecimal totalFee;

    /** 滑点成本总额 */
    private BigDecimal totalSlippageCost;

    /** 参与回测的K线数量 */
    private Integer barCount;

    // ---------- 明细 ----------

    /** 每笔交易明细(未含 taskId) */
    private List<BacktestTradeEntity> trades = new ArrayList<>();

    /** 资金曲线(未含 taskId) */
    private List<BacktestEquityCurveEntity> equityCurve = new ArrayList<>();
}
