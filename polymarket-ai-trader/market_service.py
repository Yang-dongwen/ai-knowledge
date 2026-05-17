"""
市场数据模块。
拉取 Polymarket 活跃市场、获取盘口价格、本地筛选优质市场。

核心改进：
1. 拉取 100-200 个市场，按 volume_24hr 排序
2. 正确计算单个 token 的 bid-ask spread
3. 使用 midpoint 作为参考价格
4. 本地筛选 + 评分，只把优质市场交给 AI
"""

import json
import requests
from typing import Optional
from logger_service import logger
from config import config

GAMMA_API_URL = "https://gamma-api.polymarket.com"
CLOB_API_URL = "https://clob.polymarket.com"


def fetch_active_markets(limit: int = 100) -> list[dict]:
    """
    从 Gamma API 拉取活跃市场。
    多策略拉取，本地用关键词过滤出 crypto/finance 相关市场。
    """
    all_markets = {}

    # 策略1：按 volume_24hr 降序（当前最活跃）
    try:
        resp = requests.get(f"{GAMMA_API_URL}/markets", params={
            "limit": limit, "active": True, "closed": False,
            "order": "volume_24hr", "ascending": False,
        }, timeout=15)
        resp.raise_for_status()
        for m in resp.json():
            mid = m.get("id", "")
            if mid:
                all_markets[mid] = m
        logger.info(f"策略1（24h成交量）: 获取 {len(resp.json())} 个")
    except Exception as e:
        logger.error(f"策略1失败: {e}")

    # 策略2：按 liquidity 降序（流动性最好）
    try:
        resp = requests.get(f"{GAMMA_API_URL}/markets", params={
            "limit": limit, "active": True, "closed": False,
            "order": "liquidity", "ascending": False,
        }, timeout=15)
        resp.raise_for_status()
        for m in resp.json():
            mid = m.get("id", "")
            if mid and mid not in all_markets:
                all_markets[mid] = m
        logger.info(f"策略2（流动性）: 获取 {len(resp.json())} 个")
    except Exception as e:
        logger.error(f"策略2失败: {e}")

    # 策略3：按 competitive 降序（盘口活跃）
    try:
        resp = requests.get(f"{GAMMA_API_URL}/markets", params={
            "limit": 50, "active": True, "closed": False,
            "order": "competitive", "ascending": False,
        }, timeout=15)
        resp.raise_for_status()
        for m in resp.json():
            mid = m.get("id", "")
            if mid and mid not in all_markets:
                all_markets[mid] = m
        logger.info(f"策略3（竞争度）: 获取 {len(resp.json())} 个")
    except Exception as e:
        logger.error(f"策略3失败: {e}")

    result = list(all_markets.values())

    # 本地关键词预过滤：只保留 crypto/finance/macro 相关 + 通用短期市场
    crypto_finance_keywords = [
        "btc", "bitcoin", "eth", "ethereum", "sol", "solana", "crypto",
        "token", "bnb", "xrp", "doge", "ada", "avax", "link", "price",
        "above", "below", "hit", "reach", "fed", "rate", "cpi", "inflation",
        "gdp", "fomc", "interest", "treasury", "yield", "s&p", "nasdaq",
        "stock", "market cap", "fdv", "tvl", "defi", "usdt", "usdc",
        "stablecoin", "halving", "etf", "sec", "regulation", "tariff",
        "trade war", "recession", "bull", "bear", "ath", "all-time high",
        "launch", "airdrop", "mainnet", "listing", "$"
    ]

    filtered = []
    for m in result:
        question = m.get("question", "").lower()
        # 如果问题包含任何 crypto/finance 关键词，保留
        if any(kw in question for kw in crypto_finance_keywords):
            filtered.append(m)
            continue
        # 也保留短期通用市场（today/tonight/tomorrow）
        short_keywords = ["today", "tonight", "tomorrow", "this week", "24h"]
        if any(kw in question for kw in short_keywords):
            filtered.append(m)
            continue

    # 如果过滤后太少，放宽条件，保留所有盘口活跃的
    if len(filtered) < 10:
        logger.info(f"关键词过滤后仅 {len(filtered)} 个，放宽条件保留所有市场")
        filtered = result

    logger.info(f"合并去重 {len(result)} 个 → 关键词过滤后 {len(filtered)} 个候选")
    return filtered


def get_orderbook(token_id: str) -> Optional[dict]:
    """从 CLOB API 获取指定 token 的订单簿。"""
    if not token_id:
        return None
    try:
        url = f"{CLOB_API_URL}/book"
        params = {"token_id": token_id}
        resp = requests.get(url, params=params, timeout=10)
        resp.raise_for_status()
        return resp.json()
    except Exception as e:
        logger.debug(f"获取订单簿失败 token_id={token_id[:16]}...: {e}")
        return None


def get_book_quote(book: dict) -> Optional[dict]:
    """
    从订单簿中提取最优报价。
    正确计算：best_bid, best_ask, spread, mid。
    """
    if not book:
        return None

    bids = book.get("bids") or []
    asks = book.get("asks") or []

    if not bids or not asks:
        return None

    best_bid = max(float(x["price"]) for x in bids)
    best_ask = min(float(x["price"]) for x in asks)

    # 异常检查：bid 不应该大于 ask
    if best_bid >= best_ask:
        best_bid = best_ask - 0.01

    spread = best_ask - best_bid
    mid = (best_bid + best_ask) / 2

    return {
        "best_bid": best_bid,
        "best_ask": best_ask,
        "spread": spread,
        "mid": mid,
    }


def _parse_json_field(value) -> list:
    """解析 Gamma API 返回的 JSON 字符串字段。"""
    if isinstance(value, list):
        return value
    if isinstance(value, str) and value:
        try:
            return json.loads(value)
        except (json.JSONDecodeError, TypeError):
            return []
    return []


def parse_tokens(market: dict) -> tuple[str, str]:
    """
    从市场数据中解析 YES 和 NO 的 token_id。
    返回 (yes_token_id, no_token_id)。
    """
    clob_token_ids = _parse_json_field(market.get("clobTokenIds", ""))
    outcomes = _parse_json_field(market.get("outcomes", ""))

    yes_token_id = ""
    no_token_id = ""

    if len(clob_token_ids) >= 2 and len(outcomes) >= 2:
        for i, outcome in enumerate(outcomes):
            if outcome.upper() == "YES" and i < len(clob_token_ids):
                yes_token_id = clob_token_ids[i]
            elif outcome.upper() == "NO" and i < len(clob_token_ids):
                no_token_id = clob_token_ids[i]
    elif len(clob_token_ids) >= 2:
        yes_token_id = clob_token_ids[0]
        no_token_id = clob_token_ids[1]

    return yes_token_id, no_token_id


def is_good_market(market: dict, yes_quote: dict, no_quote: dict) -> tuple[bool, str]:
    """
    本地筛选：判断市场是否值得交给 AI 分析。

    赛道分类：
    - 主赛道（Crypto/Finance/Macro）：允许 1-30 天结算
    - 补充赛道（Sports/Tech/News）：只允许 7 天内结算，需短期关键词
    - 屏蔽赛道（长期政治/年度冠军/娱乐长期）：直接过滤

    返回 (是否通过, 原因)。
    """
    question = market.get("question", "")
    question_lower = question.lower()
    category = (market.get("category") or "").lower()
    tags = (market.get("tags") or "").lower() if isinstance(market.get("tags"), str) else ""

    # ========== 赛道判断 ==========
    # 主赛道关键词
    primary_keywords = [
        "btc", "bitcoin", "eth", "ethereum", "sol", "solana", "crypto",
        "token", "bnb", "xrp", "doge", "ada", "avax", "matic", "link",
        "price", "above", "below", "hit", "reach", "fed", "rate", "cpi",
        "inflation", "gdp", "jobs", "fomc", "interest rate", "nonfarm",
        "pce", "treasury", "yield", "s&p", "nasdaq", "dow", "stock",
        "market cap", "fdv", "tvl", "defi", "usdt", "usdc", "stablecoin"
    ]
    is_primary = any(kw in question_lower for kw in primary_keywords) or \
                 any(c in category for c in ["crypto", "finance", "macro", "economy", "markets"])

    # 补充赛道关键词
    secondary_keywords = [
        "win", "beat", "score", "game", "match", "tonight", "today",
        "tomorrow", "launch", "release", "announce", "ship", "update",
        "weather", "temperature", "rain", "snow"
    ]
    short_term_keywords = ["today", "tonight", "tomorrow", "this week", "by friday",
                           "by sunday", "by monday", "24h", "24 hour"]
    is_secondary = any(kw in question_lower for kw in secondary_keywords) or \
                   any(c in category for c in ["sports", "tech", "science", "news"])
    has_short_term = any(kw in question_lower for kw in short_term_keywords)

    # 屏蔽赛道关键词
    block_long_term = [
        "champion 2027", "champion 2028", "champion 2029",
        "world cup winner", "super bowl winner", "oscar winner",
        "grammy winner", "ballon d'or", "presidential", "election 2028",
        "nominee", "nomination", "season winner", "annual",
        "wimbledon winner", "f1 drivers' champion", "mls cup",
        "premier league winner", "world series winner",
        "survivor", "bachelor", "bachelorette"
    ]
    for kw in block_long_term:
        if kw in question_lower:
            return False, f"屏蔽赛道(长期/冷门): {kw}"

    # 通用屏蔽关键词
    for kw in config.block_keywords:
        if kw in question_lower:
            return False, f"包含屏蔽关键词: {kw}"

    # ========== 到期时间判断 ==========
    end_date_str = market.get("endDate") or market.get("end_date_iso") or ""
    days_to_end = None
    if end_date_str:
        try:
            from datetime import datetime, timezone
            end_date_str_clean = end_date_str.replace("Z", "+00:00")
            if "T" in end_date_str_clean:
                end_dt = datetime.fromisoformat(end_date_str_clean)
            else:
                end_dt = datetime.strptime(end_date_str_clean[:10], "%Y-%m-%d").replace(tzinfo=timezone.utc)
            now = datetime.now(timezone.utc)
            days_to_end = (end_dt - now).total_seconds() / 86400
        except (ValueError, TypeError):
            pass

    if days_to_end is not None:
        # 已过期
        if days_to_end < 0:
            return False, f"已过期: {days_to_end:.1f}天"

        if is_primary:
            # 主赛道：允许 0-90 天（Polymarket 活跃市场多为中期）
            if days_to_end > 90:
                return False, f"主赛道但距结束太远: {days_to_end:.1f}天 > 90天"
        elif is_secondary:
            # 补充赛道：只允许 30 天内
            if days_to_end > 30:
                return False, f"补充赛道距结束太远: {days_to_end:.1f}天 > 30天"
            if not has_short_term and days_to_end > 14:
                return False, f"补充赛道无短期关键词且 > 14天: {days_to_end:.1f}天"
        else:
            # 未分类：允许 90 天内（先放宽，让市场能通过）
            if days_to_end > 90:
                return False, f"未分类赛道距结束太远: {days_to_end:.1f}天 > 90天"

    # ========== 盘口质量判断 ==========
    # 1. 单边盘口价差过滤
    if yes_quote["spread"] > config.max_token_spread:
        return False, f"YES价差过大: {yes_quote['spread']:.4f} > {config.max_token_spread}"

    if no_quote["spread"] > config.max_token_spread:
        return False, f"NO价差过大: {no_quote['spread']:.4f} > {config.max_token_spread}"

    # 2. 二元市场买入成本过滤
    buy_both_cost = yes_quote["best_ask"] + no_quote["best_ask"] - 1
    if buy_both_cost > config.max_buy_both_cost:
        return False, f"买入成本过高: {buy_both_cost:.4f} > {config.max_buy_both_cost}"

    # 3. 极端价格过滤（主赛道放宽到 0.03）
    min_price = 0.03 if is_primary else config.min_mid_price
    max_price = config.max_mid_price
    if yes_quote["mid"] < min_price or yes_quote["mid"] > max_price:
        return False, f"YES价格极端: mid={yes_quote['mid']:.4f} (范围 {min_price}-{max_price})"

    # 4. 24h 成交量过滤
    volume_24h = float(market.get("volume24hr") or market.get("volume_24hr") or 0)
    if volume_24h < config.min_volume_24h:
        total_volume = float(market.get("volume", 0) or 0)
        if total_volume < config.min_volume_usdc:
            return False, f"成交量不足: 24h={volume_24h:.0f}, total={total_volume:.0f}"

    return True, "ok"


def calc_market_score(market: dict, yes_quote: dict, no_quote: dict) -> float:
    """
    给市场打分，用于排序选出最优质的候选市场。

    赛道优先级：
    1. Crypto/Finance 短周期价格问题（最高分）
    2. 宏观数据公布问题
    3. 当天/明天结束的体育单场
    4. 产品发布 / Token Launch
    5. 其他短期问题
    """
    volume_24h = float(market.get("volume24hr") or market.get("volume_24hr") or 0)
    liquidity = float(market.get("liquidity") or 0)

    avg_spread = (yes_quote["spread"] + no_quote["spread"]) / 2
    buy_both_cost = yes_quote["best_ask"] + no_quote["best_ask"] - 1

    score = 0.0

    # 基础分：流动性和成交量
    score += min(volume_24h / 10000, 10) * 3
    score += min(liquidity / 5000, 10) * 3
    score -= avg_spread * 80
    score -= buy_both_cost * 80

    # ========== 赛道加分 ==========
    question = market.get("question", "").lower()
    category = (market.get("category") or "").lower()

    # 主赛道：Crypto 价格类（+35分）
    crypto_price_keywords = ["btc", "bitcoin", "eth", "ethereum", "sol", "solana",
                             "crypto", "bnb", "xrp", "doge", "avax", "link"]
    price_action_keywords = ["above", "below", "hit", "reach", "exceed", "over", "under", "price"]
    is_crypto = any(kw in question for kw in crypto_price_keywords) or "crypto" in category
    is_price_action = any(kw in question for kw in price_action_keywords)

    if is_crypto and is_price_action:
        score += 35
    elif is_crypto:
        score += 25

    # 主赛道：宏观/金融（+20分）
    macro_keywords = ["fed", "rate", "cpi", "inflation", "gdp", "jobs", "unemployment",
                      "fomc", "interest rate", "nonfarm", "pce", "treasury", "s&p", "nasdaq"]
    if any(kw in question for kw in macro_keywords):
        score += 20

    # 补充赛道：体育单场（+12分）
    sports_today_keywords = ["tonight", "today", "vs", "game 1", "game 2", "game 3"]
    if any(kw in question for kw in sports_today_keywords):
        score += 12

    # 补充赛道：产品发布 / Token Launch（+10分）
    launch_keywords = ["launch", "release", "announce", "fdv", "market cap", "listing",
                       "airdrop", "mainnet", "testnet", "ship"]
    if any(kw in question for kw in launch_keywords):
        score += 10

    # ========== 时间加分 ==========
    end_date_str = market.get("endDate") or market.get("end_date_iso") or ""
    if end_date_str:
        try:
            from datetime import datetime, timezone
            end_date_str_clean = end_date_str.replace("Z", "+00:00")
            if "T" in end_date_str_clean:
                end_dt = datetime.fromisoformat(end_date_str_clean)
            else:
                end_dt = datetime.strptime(end_date_str_clean[:10], "%Y-%m-%d").replace(tzinfo=timezone.utc)
            now = datetime.now(timezone.utc)
            days_to_end = (end_dt - now).total_seconds() / 86400

            # 越快结束分越高
            if 0 < days_to_end <= 0.5:
                score += 25  # 12小时内
            elif days_to_end <= 1:
                score += 20  # 当天
            elif days_to_end <= 2:
                score += 12  # 明天
            elif days_to_end <= 3:
                score += 8   # 3天内
            elif days_to_end <= 7:
                score += 4   # 一周内
        except (ValueError, TypeError):
            pass

    return round(score, 2)


def select_markets(raw_markets: list[dict], analyze_limit: int = 10, state_store=None) -> list[dict]:
    """
    从原始市场列表中筛选出优质市场。
    如果提供了 state_store，会跳过已处理的市场。
    返回带有盘口数据的市场信息列表。
    """
    candidates = []
    skipped_cached = 0

    for market in raw_markets:
        # 检查是否已处理过
        if state_store and config.skip_processed_markets:
            if state_store.is_processed(
                market,
                recheck_after_hours=config.recheck_processed_after_hours,
            ):
                skipped_cached += 1
                continue

        # 解析 token
        yes_token_id, no_token_id = parse_tokens(market)
        if not yes_token_id or not no_token_id:
            continue

        # 提前过期检查（避免浪费订单簿请求）
        end_date_str = market.get("endDate") or market.get("end_date_iso") or ""
        if end_date_str:
            try:
                from datetime import datetime, timezone
                end_date_str_clean = end_date_str.replace("Z", "+00:00")
                if "T" in end_date_str_clean:
                    end_dt = datetime.fromisoformat(end_date_str_clean)
                else:
                    end_dt = datetime.strptime(end_date_str_clean[:10], "%Y-%m-%d").replace(tzinfo=timezone.utc)
                now = datetime.now(timezone.utc)
                days_to_end = (end_dt - now).total_seconds() / 86400
                if days_to_end < 0:
                    if state_store:
                        state_store.mark_processed(market, "expired", "已过期")
                    continue
            except (ValueError, TypeError):
                pass

        # 获取订单簿
        yes_book = get_orderbook(yes_token_id)
        no_book = get_orderbook(no_token_id)

        yes_quote = get_book_quote(yes_book)
        no_quote = get_book_quote(no_book)

        if not yes_quote or not no_quote:
            if state_store:
                state_store.mark_processed(market, "orderbook_rejected", "无盘口数据")
            continue

        # 本地筛选
        ok, reason = is_good_market(market, yes_quote, no_quote)
        if not ok:
            question = market.get("question", "")[:50]
            logger.info(f"  ✗ {question}... | {reason}")
            if state_store:
                state_store.mark_processed(market, "pre_filter_rejected", reason)
            continue

        # 计算评分
        score = calc_market_score(market, yes_quote, no_quote)

        # 构建统一市场信息
        market_info = {
            "market_id": market.get("id", ""),
            "condition_id": market.get("conditionId", market.get("condition_id", "")),
            "question": market.get("question", ""),
            "description": (market.get("description", "") or "")[:500],
            "volume": float(market.get("volume", 0) or 0),
            "volume_24h": float(market.get("volume24hr") or market.get("volume_24hr") or 0),
            "liquidity": float(market.get("liquidity") or 0),
            "end_date": market.get("endDate", market.get("end_date_iso", "")),
            "yes_token_id": yes_token_id,
            "no_token_id": no_token_id,
            "yes_bid": yes_quote["best_bid"],
            "yes_ask": yes_quote["best_ask"],
            "yes_spread": yes_quote["spread"],
            "yes_mid": yes_quote["mid"],
            "no_bid": no_quote["best_bid"],
            "no_ask": no_quote["best_ask"],
            "no_spread": no_quote["spread"],
            "no_mid": no_quote["mid"],
            "buy_both_cost": yes_quote["best_ask"] + no_quote["best_ask"] - 1,
            "score": score,
            "yes_price": yes_quote["mid"],
            "no_price": no_quote["mid"],
            "yes_buy_price": yes_quote["best_ask"],
            "no_buy_price": no_quote["best_ask"],
            "spread": min(yes_quote["spread"], no_quote["spread"]),
            # 保留原始市场数据用于 state_store
            "_raw_market": market,
        }

        candidates.append((score, market_info))

    # 按评分排序，取前 N 个
    candidates.sort(key=lambda x: x[0], reverse=True)
    selected = [info for _, info in candidates[:analyze_limit]]

    if skipped_cached > 0:
        logger.info(f"↷ 跳过已处理市场: {skipped_cached} 个")
    logger.info(f"本地筛选完成: {len(raw_markets)} 个市场 → {len(candidates)} 个通过 → 选出 {len(selected)} 个交给 AI")

    return selected
