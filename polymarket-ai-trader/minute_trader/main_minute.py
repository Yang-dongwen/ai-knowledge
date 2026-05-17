"""
Polymarket 加密分钟级 AI 交易脚本 - 主入口。

专注 BTC/ETH 的 5m、15m Up/Down 市场。

修复清单（P0）：
1. _get_bool 默认值 bug ✅
2. enable_5m/15m/hourly 配置生效 ✅
3. 状态持久化，防重启重复交易 ✅
4. AI 调用后重新刷新盘口和时间 ✅
5. 程序化预信号，减少无效 AI 调用 ✅
6. 模拟盘/实盘计数分离 ✅
7. AI 输出严格校验 ✅
"""

import sys
import os
import time
import traceback
from datetime import datetime, date

# 添加父目录到 path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from minute_trader.config import config
from minute_trader.market_finder import fetch_crypto_minute_markets, get_book_quote, get_seconds_to_close
from minute_trader.price_feed import calculate_features
from minute_trader.ai_minute import analyze_minute_market
from minute_trader.signal_engine import should_call_ai
from minute_trader.state_store import MinuteStateStore
from logger_service import logger, log_signal, log_order, log_error

# 初始化状态存储
state_store = MinuteStateStore("data/minute_state.json")

# 每日计数
live_orders = 0
live_spend = 0.0
sim_orders = 0
sim_spend = 0.0
last_reset_date = date.today()


def reset_daily_counters():
    global live_orders, live_spend, sim_orders, sim_spend, last_reset_date
    today = date.today()
    if today != last_reset_date:
        live_orders = 0
        live_spend = 0.0
        sim_orders = 0
        sim_spend = 0.0
        last_reset_date = today
        logger.info("每日计数器已重置")


def detect_symbol(question: str) -> str:
    q = question.lower()
    if "bitcoin" in q or "btc" in q:
        return "BTC"
    elif "ethereum" in q or "eth" in q:
        return "ETH"
    elif "solana" in q or "sol" in q:
        return "SOL"
    return "BTC"


def calculate_edge(ai_result: dict, market_info: dict) -> tuple[str, float, str, float]:
    """返回 (side, edge, token_id, buy_price)"""
    up_prob = ai_result["estimated_up_probability"]
    side = ai_result["recommended_side"]

    if side == "UP":
        buy_price = market_info["up_ask"]
        edge = up_prob - buy_price
        token_id = market_info["up_token_id"]
    elif side == "DOWN":
        down_prob = 1.0 - up_prob
        buy_price = market_info["down_ask"]
        edge = down_prob - buy_price
        token_id = market_info["down_token_id"]
    else:
        return "HOLD", 0, "", 0

    return side, round(edge, 4), token_id, buy_price


def refresh_before_order(market_info: dict, side: str) -> tuple[bool, str, float]:
    """
    下单前重新刷新盘口，检查滑点。
    返回 (是否允许, 原因, 最新ask价格)
    """
    token_id = market_info["up_token_id"] if side == "UP" else market_info["down_token_id"]
    latest_quote = get_book_quote(token_id)

    if not latest_quote:
        return False, "无法刷新盘口", 0

    latest_ask = latest_quote["best_ask"]
    old_ask = market_info["up_ask"] if side == "UP" else market_info["down_ask"]

    # 滑点保护
    if latest_ask > old_ask + config.max_slippage:
        return False, f"盘口已变化，滑点过大: old={old_ask:.4f} new={latest_ask:.4f}", 0

    # 重新检查 spread
    if latest_quote["spread"] > config.max_token_spread:
        return False, f"最新spread过大: {latest_quote['spread']:.4f}", 0

    return True, "ok", latest_ask


def place_minute_order(token_id: str, side: str, amount: float, price: float, market_info: dict) -> dict:
    """下单（FAK）。"""
    global live_orders, live_spend, sim_orders, sim_spend

    if not config.trade_enabled:
        result = {
            "market_id": market_info["market_id"],
            "question": market_info["question"],
            "side": side,
            "amount_usdc": amount,
            "price": price,
            "status": "dry_run",
        }
        log_order(result)
        sim_orders += 1
        sim_spend += amount
        logger.info(f"  [DRY RUN] 模拟下单: {side} @ {price:.4f}, 金额={amount} USDC")
        return result

    # 实盘下单（V2 SDK）
    try:
        from py_clob_client_v2 import (
            ClobClient, ApiCreds, MarketOrderArgs, OrderType,
            PartialCreateOrderOptions, Side
        )

        creds = ApiCreds(
            api_key=config.clob_api_key,
            api_secret=config.clob_secret,
            api_passphrase=config.clob_pass_phrase
        ) if config.clob_api_key else None

        client = ClobClient(
            host="https://clob.polymarket.com",
            chain_id=137,
            key=config.private_key,
            creds=creds,
            signature_type=config.signature_type,  # 3 = POLY_1271 (Deposit Wallet)
            funder=config.funder_address,  # 你的 Deposit Wallet 地址
        )

        # 让 signer 知道 funder 地址，供 headers 使用
        if client.signer and config.funder_address:
            client.signer.funder_address = config.funder_address

        # V2 Market Order：amount 是 USDC 金额
        order_args = MarketOrderArgs(
            token_id=token_id,
            amount=amount,  # USDC
            side=Side.BUY,
            order_type=OrderType.FAK,
        )

        options = PartialCreateOrderOptions(
            tick_size="0.01",
        )

        resp = client.create_and_post_market_order(
            order_args=order_args,
            options=options,
            order_type=OrderType.FAK,
        )

        result = {
            "market_id": market_info["market_id"],
            "question": market_info["question"],
            "side": side,
            "amount_usdc": amount,
            "price": price,
            "status": "submitted",
            "order_result": resp if isinstance(resp, dict) else str(resp),
        }
        log_order(result)
        live_orders += 1
        live_spend += amount
        logger.info(f"  [LIVE] ✅ 订单已提交: {side} @ {price:.4f}, 金额={amount} USDC, resp={resp}")
        return result

    except Exception as e:
        result = {"status": "failed", "error": str(e), "market_id": market_info["market_id"]}
        log_error(result)
        logger.error(f"  下单失败: {e}")
        return result


def run_scan():
    """执行一轮分钟级市场扫描。"""
    reset_daily_counters()

    logger.info(f"\n{'─' * 50}")
    logger.info(f"分钟级扫描 | {datetime.now().strftime('%H:%M:%S')} | "
                f"实盘: {live_orders}/{config.max_daily_orders}单 {live_spend:.1f}/{config.max_daily_spend_usdc}U | "
                f"模拟: {sim_orders}单 {sim_spend:.1f}U")
    logger.info(f"{'─' * 50}")

    # 检查每日限额（只限制实盘）
    if config.trade_enabled:
        if live_orders >= config.max_daily_orders:
            logger.info("今日实盘订单数已达上限")
            return
        if live_spend >= config.max_daily_spend_usdc:
            logger.info("今日实盘交易金额已达上限")
            return

    # 1. 查找分钟级市场
    markets = fetch_crypto_minute_markets(config)
    if not markets:
        logger.info("当前无可交易的分钟级市场")
        return

    # 2. 逐个分析
    for market_info in markets:
        market_id = market_info["market_id"]
        question = market_info["question"]
        seconds_left = market_info["seconds_to_close"]

        # 防重复：同一市场只下单一次，等结算后才能交易新市场
        if state_store.has_traded(market_id):
            continue

        # 全局冷却：上一笔下单后 60 秒内不再下新单
        if hasattr(run_scan, '_last_trade_time'):
            elapsed = time.time() - run_scan._last_trade_time
            if elapsed < 60:
                continue

        logger.info(f"\n  📊 {question}")
        logger.info(f"     剩余 {seconds_left}s | UP mid={market_info['up_mid']:.4f} DOWN mid={market_info['down_mid']:.4f}")

        # 3. 获取实时价格特征
        symbol = detect_symbol(question)
        features = calculate_features(symbol)
        if not features:
            logger.warning(f"     获取 {symbol} 价格失败，跳过")
            continue

        logger.info(f"     {symbol}={features['current_price']:.2f} | 1m={features['last_1m_change_pct']:+.3f}% 3m={features['last_3m_change_pct']:+.3f}% | 动量={features['momentum']}")

        # 4. 程序化预信号（减少无效 AI 调用）
        should_ai, pre_reason, pre_side = should_call_ai(features, market_info, config)
        if not should_ai:
            logger.info(f"     → 预信号不通过: {pre_reason}")
            continue

        logger.info(f"     预信号: {pre_side} | 调用 AI...")

        # 5. AI 分析
        ai_start = time.time()
        ai_result = analyze_minute_market(market_info, features)
        ai_elapsed = time.time() - ai_start

        if not ai_result:
            logger.warning(f"     AI 分析失败（{ai_elapsed:.1f}s），跳过")
            time.sleep(3)
            continue

        side_cn = {"UP": "看涨", "DOWN": "看跌", "HOLD": "观望"}.get(ai_result["recommended_side"], "?")
        logger.info(f"     AI（{ai_elapsed:.1f}s）: Up概率={ai_result['estimated_up_probability']:.2f} 置信度={ai_result['confidence']:.2f} 方向={side_cn}")
        logger.info(f"     AI 原因: {ai_result['reason']}")

        # 6. 计算 edge
        side, edge, token_id, buy_price = calculate_edge(ai_result, market_info)
        if side == "HOLD":
            logger.info(f"     → 观望，不交易")
            continue

        logger.info(f"     Edge: {edge:.4f} (最低={config.min_edge})")

        # 7. 风控
        reject_reasons = []
        if not ai_result["should_trade"]:
            reject_reasons.append("AI不建议交易")
        if ai_result["confidence"] < config.min_ai_confidence:
            reject_reasons.append(f"置信度不足: {ai_result['confidence']:.2f}")
        if edge < config.min_edge:
            reject_reasons.append(f"edge不足: {edge:.4f}")

        if reject_reasons:
            logger.info(f"     ❌ 风控拒绝: {', '.join(reject_reasons)}")
            log_signal({
                "market_id": market_id, "question": question, "side": side,
                "edge": edge, "status": "rejected", "reasons": reject_reasons,
            })
            continue

        # 8. 下单前重新刷新盘口和时间（关键！）
        refresh_ok, refresh_reason, latest_price = refresh_before_order(market_info, side)
        if not refresh_ok:
            logger.info(f"     ❌ 下单前刷新失败: {refresh_reason}")
            continue

        # 重新检查剩余时间
        new_seconds = get_seconds_to_close({"endDate": market_info["end_date"]})
        if new_seconds is not None and new_seconds < config.min_seconds_to_close:
            logger.info(f"     ❌ AI 分析后剩余时间不足: {new_seconds}s < {config.min_seconds_to_close}s")
            continue

        # 使用最新价格
        final_price = latest_price if latest_price > 0 else buy_price

        # 重新计算 edge（用最新价格）
        if side == "UP":
            final_edge = ai_result["estimated_up_probability"] - final_price
        elif side == "DOWN":
            final_edge = (1.0 - ai_result["estimated_up_probability"]) - final_price
        else:
            final_edge = 0

        if final_edge < config.min_edge:
            logger.info(f"     ❌ 刷新后 edge 不足: {final_edge:.4f} < {config.min_edge}")
            continue

        # 9. 下单
        logger.info(f"     ✅ 风控通过! {side} @ {final_price:.4f} edge={final_edge:.4f}")

        order_result = place_minute_order(
            token_id=token_id,
            side=side,
            amount=config.trade_amount_usdc,
            price=final_price,
            market_info=market_info
        )

        # 只有成功的订单才标记已交易
        order_status = order_result.get("status", "")
        if order_status in ("dry_run", "submitted", "matched", "live"):
            state_store.mark_traded(market_id, question, side, config.trade_amount_usdc)
            run_scan._last_trade_time = time.time()  # 全局冷却
        else:
            logger.info(f"     订单未成功({order_status})，不标记已交易")

        # 记录信号
        log_signal({
            "market_id": market_id, "question": question, "side": side,
            "edge": final_edge, "confidence": ai_result["confidence"],
            "price": final_price, "amount": config.trade_amount_usdc,
            "status": order_status,
            "features": features, "ai_elapsed": ai_elapsed,
        })

        time.sleep(2)


def main():
    """主循环。"""
    logger.info("=" * 50)
    logger.info("Polymarket Crypto Minute AI Trader 启动")
    logger.info(f"模式: {'实盘' if config.trade_enabled else '模拟盘 (DRY RUN)'}")
    logger.info(f"扫描间隔: {config.scan_interval_seconds} 秒")
    logger.info(f"单笔金额: {config.trade_amount_usdc} USDC")
    logger.info(f"每日上限: {config.max_daily_orders} 单 / {config.max_daily_spend_usdc} USDC")
    logger.info(f"交易窗口: {config.min_seconds_to_close}s - {config.max_seconds_to_close}s")
    logger.info(f"最小 edge: {config.min_edge} | 最小置信度: {config.min_ai_confidence}")
    logger.info(f"市场类型: 5m={'开' if config.enable_5m else '关'} 15m={'开' if config.enable_15m else '关'} hourly={'开' if config.enable_hourly else '关'}")

    # 清理旧状态
    removed = state_store.cleanup_old(keep_hours=24)
    logger.info(f"已交易缓存: {len(state_store.traded_markets)} 条，清理 {removed} 条过期")
    logger.info("=" * 50)

    while True:
        try:
            run_scan()
        except KeyboardInterrupt:
            logger.info("用户中断，退出")
            break
        except Exception as e:
            logger.error(f"扫描异常: {e}")
            log_error({"error": str(e), "traceback": traceback.format_exc()})

        try:
            time.sleep(config.scan_interval_seconds)
        except KeyboardInterrupt:
            logger.info("用户中断，退出")
            break


if __name__ == "__main__":
    main()
