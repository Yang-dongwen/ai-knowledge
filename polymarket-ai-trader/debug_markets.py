"""
诊断脚本：查看 Gamma API 返回的市场数据，分析为什么通不过筛选。
运行：python debug_markets.py
"""

import json
import requests
from datetime import datetime, timezone

GAMMA_API_URL = "https://gamma-api.polymarket.com"
CLOB_API_URL = "https://clob.polymarket.com"


def parse_json_field(value):
    if isinstance(value, list):
        return value
    if isinstance(value, str) and value:
        try:
            return json.loads(value)
        except:
            return []
    return []


def main():
    # 拉取市场
    print("=" * 60)
    print("策略1: 按 end_date 升序")
    print("=" * 60)
    resp = requests.get(f"{GAMMA_API_URL}/markets", params={
        "limit": 20, "active": True, "closed": False,
        "order": "end_date", "ascending": True
    }, timeout=15)
    markets_by_end = resp.json()

    print(f"\n获取到 {len(markets_by_end)} 个市场\n")
    for i, m in enumerate(markets_by_end[:10]):
        question = m.get("question", "")[:60]
        end_date = m.get("endDate", "N/A")
        volume = float(m.get("volume", 0) or 0)
        volume_24h = float(m.get("volume24hr") or m.get("volume_24hr") or 0)
        clob_ids = parse_json_field(m.get("clobTokenIds", ""))
        outcomes = parse_json_field(m.get("outcomes", ""))
        outcome_prices = parse_json_field(m.get("outcomePrices", ""))
        category = m.get("category", "")

        # 计算到期天数
        days_to_end = "?"
        if end_date and end_date != "N/A":
            try:
                ed = end_date.replace("Z", "+00:00")
                if "T" in ed:
                    end_dt = datetime.fromisoformat(ed)
                else:
                    end_dt = datetime.strptime(ed[:10], "%Y-%m-%d").replace(tzinfo=timezone.utc)
                days_to_end = f"{(end_dt - datetime.now(timezone.utc)).total_seconds() / 86400:.1f}"
            except:
                pass

        print(f"[{i+1}] {question}")
        print(f"    endDate={end_date} | 剩余={days_to_end}天")
        print(f"    volume={volume:,.0f} | 24h={volume_24h:,.0f} | category={category}")
        print(f"    outcomes={outcomes} | prices={outcome_prices}")
        print(f"    clobTokenIds={len(clob_ids)}个: {clob_ids[:1]}...")

        # 尝试获取订单簿
        if len(clob_ids) >= 2:
            for idx, token_id in enumerate(clob_ids[:2]):
                try:
                    book_resp = requests.get(f"{CLOB_API_URL}/book", params={"token_id": token_id}, timeout=10)
                    book = book_resp.json()
                    bids = book.get("bids", [])
                    asks = book.get("asks", [])
                    if bids and asks:
                        best_bid = max(float(x["price"]) for x in bids)
                        best_ask = min(float(x["price"]) for x in asks)
                        spread = best_ask - best_bid
                        side_name = outcomes[idx] if idx < len(outcomes) else f"token{idx}"
                        print(f"    {side_name}: bid={best_bid:.4f} ask={best_ask:.4f} spread={spread:.4f} ✅")
                    else:
                        side_name = outcomes[idx] if idx < len(outcomes) else f"token{idx}"
                        print(f"    {side_name}: bids={len(bids)} asks={len(asks)} ❌ 无盘口")
                except Exception as e:
                    print(f"    token{idx}: 获取失败 {e}")
        else:
            print(f"    ❌ 无 clobTokenIds")
        print()

    # 策略2
    print("\n" + "=" * 60)
    print("策略2: 按 volume_24hr 降序")
    print("=" * 60)
    resp2 = requests.get(f"{GAMMA_API_URL}/markets", params={
        "limit": 10, "active": True, "closed": False,
        "order": "volume_24hr", "ascending": False
    }, timeout=15)
    markets_by_vol = resp2.json()

    print(f"\n获取到 {len(markets_by_vol)} 个市场\n")
    for i, m in enumerate(markets_by_vol[:5]):
        question = m.get("question", "")[:60]
        end_date = m.get("endDate", "N/A")
        volume_24h = float(m.get("volume24hr") or m.get("volume_24hr") or 0)
        clob_ids = parse_json_field(m.get("clobTokenIds", ""))
        outcomes = parse_json_field(m.get("outcomes", ""))

        days_to_end = "?"
        if end_date and end_date != "N/A":
            try:
                ed = end_date.replace("Z", "+00:00")
                if "T" in ed:
                    end_dt = datetime.fromisoformat(ed)
                else:
                    end_dt = datetime.strptime(ed[:10], "%Y-%m-%d").replace(tzinfo=timezone.utc)
                days_to_end = f"{(end_dt - datetime.now(timezone.utc)).total_seconds() / 86400:.1f}"
            except:
                pass

        print(f"[{i+1}] {question}")
        print(f"    24h_vol={volume_24h:,.0f} | 剩余={days_to_end}天 | tokens={len(clob_ids)}")

        if len(clob_ids) >= 2:
            for idx, token_id in enumerate(clob_ids[:2]):
                try:
                    book_resp = requests.get(f"{CLOB_API_URL}/book", params={"token_id": token_id}, timeout=10)
                    book = book_resp.json()
                    bids = book.get("bids", [])
                    asks = book.get("asks", [])
                    if bids and asks:
                        best_bid = max(float(x["price"]) for x in bids)
                        best_ask = min(float(x["price"]) for x in asks)
                        spread = best_ask - best_bid
                        side_name = outcomes[idx] if idx < len(outcomes) else f"token{idx}"
                        print(f"    {side_name}: bid={best_bid:.4f} ask={best_ask:.4f} spread={spread:.4f}")
                    else:
                        side_name = outcomes[idx] if idx < len(outcomes) else f"token{idx}"
                        print(f"    {side_name}: 无盘口 (bids={len(bids)} asks={len(asks)})")
                except Exception as e:
                    print(f"    获取失败: {e}")
        print()


if __name__ == "__main__":
    main()
