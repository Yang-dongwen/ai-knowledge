"""
加密分钟级市场筛选模块。
专门查找 BTC/ETH 的 5m、15m、hourly Up/Down 市场。

关键发现：这些市场是动态创建的 event，每 5 分钟一个新 event。
slug 格式为 btc-updown-5m-{unix_timestamp}。
需要通过构造 slug 或搜索 series 来找到当前活跃的市场。
"""

import json
import requests
from datetime import datetime, timezone
from typing import Optional
from logger_service import logger

GAMMA_API_URL = "https://gamma-api.polymarket.com"
CLOB_API_URL = "https://clob.polymarket.com"


def _parse_json_field(value) -> list:
    if isinstance(value, list):
        return value
    if isinstance(value, str) and value:
        try:
            return json.loads(value)
        except:
            return []
    return []


def get_seconds_to_close(market: dict) -> Optional[int]:
    """计算距离结算的秒数。"""
    end_date_str = market.get("endDate") or market.get("end_date_iso") or ""
    if not end_date_str:
        return None
    try:
        end_date_str = end_date_str.replace("Z", "+00:00")
        if "T" in end_date_str:
            end_dt = datetime.fromisoformat(end_date_str)
        else:
            end_dt = datetime.strptime(end_date_str[:10], "%Y-%m-%d").replace(tzinfo=timezone.utc)
        now = datetime.now(timezone.utc)
        seconds = (end_dt - now).total_seconds()
        return int(seconds) if seconds > 0 else 0
    except:
        return None


def get_book_quote(token_id: str) -> Optional[dict]:
    """获取 token 的盘口报价。"""
    if not token_id:
        return None
    try:
        resp = requests.get(f"{CLOB_API_URL}/book", params={"token_id": token_id}, timeout=8)
        resp.raise_for_status()
        book = resp.json()

        bids = book.get("bids") or []
        asks = book.get("asks") or []

        if not bids or not asks:
            return None

        best_bid = max(float(x["price"]) for x in bids)
        best_ask = min(float(x["price"]) for x in asks)

        if best_bid >= best_ask:
            return None

        return {
            "best_bid": best_bid,
            "best_ask": best_ask,
            "spread": best_ask - best_bid,
            "mid": (best_bid + best_ask) / 2,
        }
    except:
        return None


def fetch_crypto_minute_markets(config) -> list[dict]:
    """
    查找当前活跃的加密分钟级市场。

    策略：
    1. 根据当前时间计算可能的 slug（btc-updown-5m-{timestamp}）
    2. 逐个查询 Gamma API 的 events/slug 端点
    3. 从返回的 event 中提取 markets
    4. 过滤出在交易窗口内、盘口健康的市场
    """
    now = datetime.now(timezone.utc)
    now_ts = int(now.timestamp())

    # 构造候选 slug 列表
    slug_prefixes = []
    if config.enable_5m:
        slug_prefixes.extend([("btc-updown-5m", 300), ("eth-updown-5m", 300)])
    if config.enable_15m:
        slug_prefixes.extend([("btc-updown-15m", 900), ("eth-updown-15m", 900)])
    if config.enable_hourly:
        slug_prefixes.extend([("btc-updown-1h", 3600), ("eth-updown-1h", 3600)])

    candidate_slugs = []
    for prefix, interval in slug_prefixes:
        current_period = (now_ts // interval) * interval
        # 当前周期和前后几个周期
        for offset in range(-1, 4):
            ts = current_period + offset * interval
            candidate_slugs.append(f"{prefix}-{ts}")

    # 通过 slug 查找活跃市场
    all_markets = []
    found_slugs = 0

    for slug in candidate_slugs:
        try:
            resp = requests.get(f"{GAMMA_API_URL}/events/slug/{slug}", timeout=6)
            if resp.status_code != 200:
                continue

            event = resp.json()
            if not event or not isinstance(event, dict):
                continue

            found_slugs += 1
            markets = event.get("markets", [])

            for m in markets:
                # 只要活跃且接受订单的
                if m.get("closed"):
                    continue
                if not m.get("acceptingOrders", True):
                    continue
                all_markets.append(m)

        except:
            continue

    if not all_markets:
        logger.info(f"找到 0 个加密分钟级市场 | 尝试 {len(candidate_slugs)} 个slug, 命中event={found_slugs}")
        return []

    # 过滤和构建结果
    results = []

    for market in all_markets:
        question = market.get("question", "")

        # 检查时间窗口
        seconds_to_close = get_seconds_to_close(market)
        if seconds_to_close is None:
            continue
        if seconds_to_close <= 0:
            continue
        if seconds_to_close < config.min_seconds_to_close:
            continue
        if seconds_to_close > config.max_seconds_to_close:
            continue

        # 解析 token
        clob_token_ids = _parse_json_field(market.get("clobTokenIds", ""))
        outcomes = _parse_json_field(market.get("outcomes", ""))

        if len(clob_token_ids) < 2:
            continue

        # 确定 Up/Down token（outcomes 通常是 ["Up", "Down"]）
        up_token_id = ""
        down_token_id = ""

        for i, outcome in enumerate(outcomes):
            outcome_upper = outcome.upper()
            if outcome_upper in ("UP", "YES") and i < len(clob_token_ids):
                up_token_id = clob_token_ids[i]
            elif outcome_upper in ("DOWN", "NO") and i < len(clob_token_ids):
                down_token_id = clob_token_ids[i]

        if not up_token_id and len(clob_token_ids) >= 2:
            up_token_id = clob_token_ids[0]
            down_token_id = clob_token_ids[1]

        if not up_token_id or not down_token_id:
            continue

        # 获取盘口
        up_quote = get_book_quote(up_token_id)
        down_quote = get_book_quote(down_token_id)

        if not up_quote or not down_quote:
            continue

        # 盘口质量检查
        if up_quote["spread"] > config.max_token_spread:
            continue
        if down_quote["spread"] > config.max_token_spread:
            continue

        buy_both_cost = up_quote["best_ask"] + down_quote["best_ask"] - 1
        if buy_both_cost > config.max_buy_both_cost:
            continue

        market_info = {
            "market_id": market.get("id", ""),
            "condition_id": market.get("conditionId", ""),
            "question": question,
            "end_date": market.get("endDate", ""),
            "seconds_to_close": seconds_to_close,
            "up_token_id": up_token_id,
            "down_token_id": down_token_id,
            "up_bid": up_quote["best_bid"],
            "up_ask": up_quote["best_ask"],
            "up_spread": up_quote["spread"],
            "up_mid": up_quote["mid"],
            "down_bid": down_quote["best_bid"],
            "down_ask": down_quote["best_ask"],
            "down_spread": down_quote["spread"],
            "down_mid": down_quote["mid"],
            "buy_both_cost": buy_both_cost,
            "volume_24h": float(market.get("volume24hr") or 0),
        }

        results.append(market_info)

    logger.info(f"找到 {len(results)} 个加密分钟级市场（交易窗口内）| slug命中={found_slugs} 原始市场={len(all_markets)}")
    return results
