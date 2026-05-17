"""
分钟级交易配置模块。
"""

import os
from dataclasses import dataclass
from dotenv import load_dotenv

load_dotenv()


def _get(key: str, default: str = "") -> str:
    return os.getenv(key, default).strip()


def _get_float(key: str, default: float = 0.0) -> float:
    val = _get(key)
    return float(val) if val else default


def _get_int(key: str, default: int = 0) -> int:
    val = _get(key)
    return int(val) if val else default


def _get_bool(key: str, default: bool = False) -> bool:
    val = os.getenv(key)
    if val is None or val.strip() == "":
        return default
    return val.strip().lower() in ("true", "1", "yes", "y", "on")


@dataclass
class MinuteConfig:
    # Polymarket
    private_key: str = _get("POLYMARKET_PRIVATE_KEY")
    funder_address: str = _get("POLYMARKET_FUNDER_ADDRESS")
    signature_type: int = _get_int("POLYMARKET_SIGNATURE_TYPE", 0)
    clob_api_key: str = _get("CLOB_API_KEY")
    clob_secret: str = _get("CLOB_SECRET")
    clob_pass_phrase: str = _get("CLOB_PASS_PHRASE")

    # AI
    ai_provider: str = _get("AI_PROVIDER", "nvidia")
    ai_model: str = _get("AI_MODEL", "")
    ai_temperature: float = _get_float("AI_TEMPERATURE", 0.2)
    ai_timeout_seconds: int = _get_int("AI_TIMEOUT_SECONDS", 30)

    # Trading
    trade_enabled: bool = _get_bool("TRADE_ENABLED", False)
    trade_amount_usdc: float = _get_float("TRADE_AMOUNT_USDC", 1.0)
    max_daily_orders: int = _get_int("MAX_DAILY_ORDERS", 20)
    max_daily_spend_usdc: float = _get_float("MAX_DAILY_SPEND_USDC", 20.0)

    # 时间窗口（秒）
    min_seconds_to_close: int = _get_int("MIN_SECONDS_TO_CLOSE", 60)
    max_seconds_to_close: int = _get_int("MAX_SECONDS_TO_CLOSE", 240)

    # 盘口风控
    max_token_spread: float = _get_float("MAX_TOKEN_SPREAD", 0.04)
    max_buy_both_cost: float = _get_float("MAX_BUY_BOTH_COST", 0.08)
    max_slippage: float = _get_float("MAX_SLIPPAGE", 0.02)

    # 策略
    min_ai_confidence: float = _get_float("MIN_AI_CONFIDENCE", 0.65)
    min_edge: float = _get_float("MIN_EDGE", 0.08)

    # 市场类型
    enable_5m: bool = _get_bool("ENABLE_5M_MARKETS", True)
    enable_15m: bool = _get_bool("ENABLE_15M_MARKETS", True)
    enable_hourly: bool = _get_bool("ENABLE_HOURLY_MARKETS", False)

    # 扫描间隔
    scan_interval_seconds: int = _get_int("SCAN_INTERVAL_SECONDS", 15)

    # 防重复
    one_trade_per_market: bool = _get_bool("ONE_TRADE_PER_MARKET", True)
    cooldown_seconds: int = _get_int("COOLDOWN_SECONDS", 180)


config = MinuteConfig()
