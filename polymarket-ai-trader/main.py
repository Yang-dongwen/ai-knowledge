"""
Polymarket AI 自动交易脚本 - 主入口。

流程：
1. 从 Gamma API 拉取 100+ 个活跃市场
2. 逐个获取订单簿，本地筛选出盘口健康的市场
3. 按质量评分排序，选出 Top N 个
4. 只对这些优质市场调用 AI 分析
5. 计算 edge，执行风控
6. 满足条件则下单（或模拟运行）
"""

import time
import traceback
from datetime import datetime
from config import config
from market_service import fetch_active_markets, select_markets
from ai_provider import analyze_market
from strategy import calculate_edge
from risk_control import check_risk
from order_service import place_order
from state_store import ProcessedMarketStore
from logger_service import logger, log_signal, log_error

# 状态翻译映射
SIDE_CN = {"YES": "买入YES", "NO": "买入NO", "HOLD": "观望"}
RISK_CN = {"low": "低风险", "medium": "中风险", "high": "高风险"}

# 初始化状态缓存
state_store = ProcessedMarketStore("data/processed_markets.json")


def run_scan():
    """执行一轮市场扫描和交易判断。"""
    logger.info("=" * 60)
    logger.info(f"开始新一轮市场扫描 | {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    logger.info(f"交易开关: {'实盘' if config.trade_enabled else '模拟盘'} | 单笔金额: {config.trade_amount_usdc} USDC")
    logger.info(f"AI 供应商: {config.ai_provider} / {config.ai_model}")

    # 缓存统计
    cache_stats = state_store.get_stats()
    logger.info(f"已处理缓存: {cache_stats.get('total', 0)} 条")
    logger.info("=" * 60)

    # 1. 拉取大量候选市场
    raw_markets = fetch_active_markets(limit=config.market_fetch_limit)
    if not raw_markets:
        logger.warning("未获取到市场数据")
        return

    # 2. 本地筛选 + 评分 + 排序，选出优质市场
    logger.info("正在获取订单簿并筛选优质市场...")
    selected_markets = select_markets(raw_markets, analyze_limit=config.market_analyze_limit, state_store=state_store)

    if not selected_markets:
        logger.warning("本轮无优质市场通过筛选，跳过 AI 分析")
        return

    # 3. 逐个调用 AI 分析
    logger.info(f"\n{'─' * 40}")
    logger.info(f"开始 AI 分析 ({len(selected_markets)} 个市场)")
    logger.info(f"{'─' * 40}")

    trade_count = 0

    for i, market_info in enumerate(selected_markets):
        try:
            question = market_info["question"]
            logger.info(f"\n[{i+1}/{len(selected_markets)}] {question}")
            logger.info(
                f"  YES bid={market_info['yes_bid']:.4f} ask={market_info['yes_ask']:.4f} spread={market_info['yes_spread']:.4f} | "
                f"NO bid={market_info['no_bid']:.4f} ask={market_info['no_ask']:.4f} spread={market_info['no_spread']:.4f}"
            )
            logger.info(
                f"  mid: YES={market_info['yes_mid']:.4f} NO={market_info['no_mid']:.4f} | "
                f"买入成本={market_info['buy_both_cost']:.4f} | 评分={market_info['score']:.1f} | "
                f"24h量={market_info['volume_24h']:,.0f}"
            )

            # 调用 AI 分析
            logger.info(f"  调用 AI 分析中...")
            ai_start = time.time()
            ai_result = analyze_market(market_info)
            ai_elapsed = time.time() - ai_start

            if not ai_result:
                logger.warning(f"  AI 分析失败（耗时 {ai_elapsed:.1f}s），跳过")
                # 被限流时等待更长时间
                time.sleep(5)
                continue

            side_cn = SIDE_CN.get(ai_result['recommended_side'], ai_result['recommended_side'])
            risk_cn = RISK_CN.get(ai_result.get('risk_level', ''), ai_result.get('risk_level', ''))
            question_cn = ai_result.get('question_cn', question)
            logger.info(f"  📌 问题: {question_cn}")
            logger.info(f"  AI 结果（耗时 {ai_elapsed:.1f}s）: 概率={ai_result['estimated_probability']:.2f} 置信度={ai_result['confidence']:.2f} 方向={side_cn} 风险={risk_cn}")
            logger.info(f"  AI 原因: {ai_result['reason']}")

            # 计算 edge（使用 best_ask 作为实际买入价）
            strategy_result = calculate_edge(ai_result, market_info)
            edge_status = "✅ 有优势" if strategy_result['edge'] >= config.min_edge else "❌ 优势不足"
            logger.info(f"  Edge: {strategy_result['edge']:.4f} (最低要求={config.min_edge}) {edge_status}")

            # 风控校验
            risk_result = check_risk(ai_result, strategy_result, market_info)

            # 记录信号日志
            signal = {
                "market_id": market_info["market_id"],
                "question": question,
                "question_cn": question_cn,
                "yes_mid": market_info["yes_mid"],
                "no_mid": market_info["no_mid"],
                "yes_ask": market_info["yes_ask"],
                "no_ask": market_info["no_ask"],
                "yes_spread": market_info["yes_spread"],
                "no_spread": market_info["no_spread"],
                "buy_both_cost": market_info["buy_both_cost"],
                "volume_24h": market_info["volume_24h"],
                "score": market_info["score"],
                "ai_probability": ai_result["estimated_probability"],
                "ai_confidence": ai_result["confidence"],
                "recommended_side": ai_result["recommended_side"],
                "should_trade": ai_result["should_trade"],
                "edge": strategy_result["edge"],
                "risk_allowed": risk_result["allowed"],
                "reject_reasons": risk_result["reject_reasons"],
                "trade_enabled": config.trade_enabled,
                "status": "pending"
            }

            if not risk_result["allowed"]:
                signal["status"] = "rejected"
                log_signal(signal)
                reasons_cn = [r for r in risk_result['reject_reasons']]
                logger.info(f"  ❌ 风控拒绝: {', '.join(reasons_cn)}")
                # 标记为已处理
                raw_market = market_info.get("_raw_market", market_info)
                state_store.mark_processed(raw_market, "risk_rejected", ', '.join(reasons_cn))
                continue

            # 下单
            signal["status"] = "trade" if config.trade_enabled else "dry_run"
            log_signal(signal)

            side_display = SIDE_CN.get(strategy_result['side'], strategy_result['side'])
            logger.info(f"  ✅ 风控通过! 方向={side_display} edge={strategy_result['edge']:.4f}")

            order_result = place_order(
                token_id=strategy_result["token_id"],
                side=strategy_result["side"],
                amount_usdc=config.trade_amount_usdc,
                price=strategy_result["buy_price"],
                market_info=market_info
            )

            trade_count += 1
            logger.info(f"  订单状态: {order_result.get('status', 'unknown')}")

            # 标记为已处理（交易信号）
            raw_market = market_info.get("_raw_market", market_info)
            trade_status = "trade_executed" if config.trade_enabled else "trade_signal_dry_run"
            state_store.mark_processed(raw_market, trade_status, f"side={strategy_result['side']} edge={strategy_result['edge']:.4f}")

        except Exception as e:
            logger.error(f"  处理市场异常: {e}")
            log_error({
                "market_id": market_info.get("market_id", ""),
                "question": market_info.get("question", ""),
                "error": str(e),
                "traceback": traceback.format_exc()
            })
            continue

        finally:
            # 每次 AI 调用后等待，避免触发速率限制
            time.sleep(3)

    logger.info(f"\n本轮完成: 分析 {len(selected_markets)} 个市场，触发 {trade_count} 笔交易 | {datetime.now().strftime('%H:%M:%S')}")


def main():
    """主循环。"""
    logger.info("Polymarket AI Trader 启动")
    logger.info(f"模式: {'实盘' if config.trade_enabled else '模拟盘 (DRY RUN)'}")
    logger.info(f"扫描间隔: {config.scan_interval_seconds} 秒")
    logger.info(f"市场获取: {config.market_fetch_limit} 个 → 筛选 → AI分析 {config.market_analyze_limit} 个")

    # 初始化缓存
    removed = state_store.cleanup_expired(config.processed_cache_keep_days)
    cache_stats = state_store.get_stats()
    logger.info(f"已处理缓存: 加载 {cache_stats.get('total', 0)} 条，清理过期 {removed} 条")
    if config.recheck_processed_after_hours:
        logger.info(f"重新分析间隔: {config.recheck_processed_after_hours} 小时")
    else:
        logger.info(f"重新分析: 已处理的市场不再重复分析")

    # 校验配置
    try:
        config.validate()
    except ValueError as e:
        logger.error(f"配置校验失败: {e}")
        return

    while True:
        try:
            run_scan()
        except KeyboardInterrupt:
            logger.info("用户中断，程序退出")
            break
        except Exception as e:
            logger.error(f"扫描异常: {e}")
            log_error({"error": str(e), "traceback": traceback.format_exc()})

        logger.info(f"等待 {config.scan_interval_seconds} 秒后开始下一轮...")
        try:
            time.sleep(config.scan_interval_seconds)
        except KeyboardInterrupt:
            logger.info("用户中断，程序退出")
            break


if __name__ == "__main__":
    import sys
    if len(sys.argv) > 1 and sys.argv[1] == "--debug":
        # 诊断模式：打印市场原始数据，不调用 AI
        from market_service import fetch_active_markets, parse_tokens, get_orderbook, get_book_quote
        from datetime import datetime, timezone

        logger.info("===== 诊断模式 =====")
        raw_markets = fetch_active_markets(limit=config.market_fetch_limit)
        logger.info(f"共获取 {len(raw_markets)} 个市场，逐个检查前 20 个：\n")

        checked = 0
        for m in raw_markets:
            if checked >= 20:
                break

            question = m.get("question", "")[:70]
            end_date = m.get("endDate") or m.get("end_date_iso") or ""
            volume_24h = float(m.get("volume24hr") or m.get("volume_24hr") or 0)
            volume = float(m.get("volume", 0) or 0)

            # 到期天数
            days_str = "无日期"
            if end_date:
                try:
                    ed = end_date.replace("Z", "+00:00")
                    if "T" in ed:
                        end_dt = datetime.fromisoformat(ed)
                    else:
                        end_dt = datetime.strptime(ed[:10], "%Y-%m-%d").replace(tzinfo=timezone.utc)
                    days = (end_dt - datetime.now(timezone.utc)).total_seconds() / 86400
                    days_str = f"{days:.1f}天"
                    if days < 0:
                        logger.info(f"  [{checked+1}] {question} | 已过期({days_str}) 跳过")
                        continue
                except:
                    pass

            yes_token_id, no_token_id = parse_tokens(m)
            if not yes_token_id:
                logger.info(f"  [{checked+1}] {question} | 无token 跳过")
                continue

            checked += 1

            # 获取订单簿
            yes_book = get_orderbook(yes_token_id)
            no_book = get_orderbook(no_token_id)
            yes_quote = get_book_quote(yes_book)
            no_quote = get_book_quote(no_book)

            yes_info = f"bid={yes_quote['best_bid']:.4f} ask={yes_quote['best_ask']:.4f} spread={yes_quote['spread']:.4f}" if yes_quote else "无盘口"
            no_info = f"bid={no_quote['best_bid']:.4f} ask={no_quote['best_ask']:.4f} spread={no_quote['spread']:.4f}" if no_quote else "无盘口"

            status = "✅" if (yes_quote and no_quote and yes_quote["spread"] <= 0.10 and no_quote["spread"] <= 0.10) else "❌"

            logger.info(f"  [{checked}] {status} {question}")
            logger.info(f"      剩余={days_str} | 24h量={volume_24h:,.0f} | 总量={volume:,.0f}")
            logger.info(f"      YES: {yes_info}")
            logger.info(f"      NO:  {no_info}")
            if yes_quote and no_quote:
                cost = yes_quote["best_ask"] + no_quote["best_ask"] - 1
                logger.info(f"      买入成本={cost:.4f} | YES_mid={yes_quote['mid']:.4f}")
            logger.info("")

        logger.info("===== 诊断结束 =====")
    else:
        main()
