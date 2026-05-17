"""
实时价格数据模块。
获取 BTC/ETH/SOL 当前价格和短线特征。
使用 CoinGecko 免费 API 或 Binance 公开 API。
"""

import requests
from typing import Optional
from logger_service import logger

# Binance 公开 API（无需认证）
BINANCE_API = "https://api.binance.com/api/v3"


def get_crypto_price(symbol: str = "BTC") -> Optional[float]:
    """获取加密货币当前价格（USDT）。"""
    pair_map = {
        "BTC": "BTCUSDT",
        "ETH": "ETHUSDT",
        "SOL": "SOLUSDT",
        "XRP": "XRPUSDT",
        "DOGE": "DOGEUSDT",
    }
    pair = pair_map.get(symbol.upper(), f"{symbol.upper()}USDT")

    try:
        resp = requests.get(f"{BINANCE_API}/ticker/price", params={"symbol": pair}, timeout=5)
        resp.raise_for_status()
        return float(resp.json()["price"])
    except Exception as e:
        logger.debug(f"获取 {symbol} 价格失败: {e}")
        return None


def get_kline_data(symbol: str = "BTC", interval: str = "1m", limit: int = 5) -> list[dict]:
    """
    获取最近 K 线数据。
    interval: 1m, 3m, 5m, 15m, 1h
    """
    pair_map = {
        "BTC": "BTCUSDT",
        "ETH": "ETHUSDT",
        "SOL": "SOLUSDT",
    }
    pair = pair_map.get(symbol.upper(), f"{symbol.upper()}USDT")

    try:
        resp = requests.get(f"{BINANCE_API}/klines", params={
            "symbol": pair,
            "interval": interval,
            "limit": limit,
        }, timeout=5)
        resp.raise_for_status()
        data = resp.json()

        klines = []
        for k in data:
            klines.append({
                "open": float(k[1]),
                "high": float(k[2]),
                "low": float(k[3]),
                "close": float(k[4]),
                "volume": float(k[5]),
                "close_time": k[6],
            })
        return klines
    except Exception as e:
        logger.debug(f"获取 {symbol} K线失败: {e}")
        return []


def calculate_features(symbol: str = "BTC") -> Optional[dict]:
    """
    计算短线交易特征。
    返回当前价格、涨跌幅、动量等。
    """
    current_price = get_crypto_price(symbol)
    if not current_price:
        return None

    # 获取最近 5 根 1 分钟 K 线
    klines_1m = get_kline_data(symbol, "1m", 5)
    # 获取最近 3 根 5 分钟 K 线
    klines_5m = get_kline_data(symbol, "5m", 3)

    if not klines_1m:
        return None

    # 计算特征
    last_1m_open = klines_1m[-1]["open"]
    last_1m_change = (current_price - last_1m_open) / last_1m_open

    # 过去 3 分钟变化
    if len(klines_1m) >= 3:
        price_3m_ago = klines_1m[-3]["open"]
        change_3m = (current_price - price_3m_ago) / price_3m_ago
    else:
        change_3m = 0

    # 过去 5 分钟变化
    if klines_5m:
        price_5m_open = klines_5m[-1]["open"]
        change_5m = (current_price - price_5m_open) / price_5m_open
    else:
        change_5m = 0

    # 波动率（过去 5 根 1m K 线的高低差平均）
    if klines_1m:
        volatility = sum((k["high"] - k["low"]) / k["open"] for k in klines_1m) / len(klines_1m)
    else:
        volatility = 0

    # 动量方向
    up_count = sum(1 for k in klines_1m if k["close"] > k["open"])
    momentum = "UP" if up_count >= 3 else ("DOWN" if up_count <= 1 else "NEUTRAL")

    return {
        "symbol": symbol,
        "current_price": current_price,
        "last_1m_change_pct": round(last_1m_change * 100, 4),
        "last_3m_change_pct": round(change_3m * 100, 4),
        "last_5m_change_pct": round(change_5m * 100, 4),
        "volatility_pct": round(volatility * 100, 4),
        "momentum": momentum,
        "up_candles_5": up_count,
    }
