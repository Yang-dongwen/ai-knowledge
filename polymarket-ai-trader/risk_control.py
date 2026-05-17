"""
风控模块。
独立于 AI，由代码决定是否允许下单。
AI 可以建议交易，但最终是否下单由风控规则决定。
"""

import json
import os
from datetime import datetime, timezone, date
from config import config
from logger_service import logger, LOGS_DIR


def _count_today_orders() -> tuple[int, float]:
    """统计今日已下单数量和累计金额。"""
    orders_file = os.path.join(LOGS_DIR, "orders.jsonl")
    if not os.path.exists(orders_file):
        return 0, 0.0

    today_str = date.today().isoformat()
    count = 0
    total_spend = 0.0

    with open(orders_file, "r", encoding="utf-8") as f:
        for line in f:
            try:
                order = json.loads(line.strip())
                order_time = order.get("time", "")
                if order_time.startswith(today_str):
                    count += 1
                    total_spend += float(order.get("amount_usdc", 0))
            except (json.JSONDecodeError, ValueError):
                continue

    return count, total_spend


def check_risk(ai_result: dict, strategy_result: dict, market_info: dict) -> dict:
    """
    执行风控校验。
    返回 {"allowed": bool, "reject_reasons": list}
    """
    reasons = []

    # 1. AI 是否建议交易
    if not ai_result.get("should_trade", False):
        reasons.append("AI不建议交易")

    # 2. AI 推荐方向
    if ai_result.get("recommended_side") == "HOLD":
        reasons.append("AI推荐观望(HOLD)")

    # 3. AI 置信度
    confidence = ai_result.get("confidence", 0)
    if confidence < config.min_ai_confidence:
        reasons.append(f"AI置信度不足: {confidence:.2f} < {config.min_ai_confidence}")

    # 4. Edge 不足
    edge = strategy_result.get("edge", 0)
    if edge < config.min_edge:
        reasons.append(f"优势(Edge)不足: {edge:.4f} < {config.min_edge}")

    # 5. 市场成交量
    volume = market_info.get("volume", 0)
    if volume < config.min_volume_usdc:
        reasons.append(f"成交量不足: {volume:,.0f} < {config.min_volume_usdc:,.0f}")

    # 6. 盘口价差
    spread = market_info.get("spread", 1)
    if spread > config.max_spread:
        reasons.append(f"盘口价差过大: {spread:.4f} > {config.max_spread}")

    # 7. 每日订单数和金额限制
    today_orders, today_spend = _count_today_orders()
    if today_orders >= config.max_daily_orders:
        reasons.append(f"今日订单数已达上限: {today_orders} >= {config.max_daily_orders}")
    if today_spend + config.trade_amount_usdc > config.max_daily_spend_usdc:
        reasons.append(f"今日交易金额将超限: {today_spend + config.trade_amount_usdc:.2f} > {config.max_daily_spend_usdc}")

    # 8. 屏蔽关键词
    question = market_info.get("question", "").lower()
    for kw in config.block_keywords:
        if kw in question:
            reasons.append(f"包含屏蔽关键词: {kw}")
            break

    allowed = len(reasons) == 0
    return {"allowed": allowed, "reject_reasons": reasons}
