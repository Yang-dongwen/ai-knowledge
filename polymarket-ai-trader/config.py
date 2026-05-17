"""
配置模块。
读取 .env 文件，转换数据类型，校验必要参数，提供统一 Config 对象。
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
    val = _get(key).lower()
    return val in ("true", "1", "yes")


@dataclass
class Config:
    # Polymarket
    private_key: str = _get("POLYMARKET_PRIVATE_KEY")
    funder_address: str = _get("POLYMARKET_FUNDER_ADDRESS")
    signature_type: int = _get_int("POLYMARKET_SIGNATURE_TYPE", 0)
    clob_api_key: str = _get("CLOB_API_KEY")
    clob_secret: str = _get("CLOB_SECRET")
    clob_pass_phrase: str = _get("CLOB_PASS_PHRASE")

    # AI Provider
    ai_provider: str = _get("AI_PROVIDER", "openai")
    ai_model: str = _get("AI_MODEL", "gpt-4.1-mini")
    ai_temperature: float = _get_float("AI_TEMPERATURE", 0.2)
    ai_timeout_seconds: int = _get_int("AI_TIMEOUT_SECONDS", 60)

    openai_api_key: str = _get("OPENAI_API_KEY")
    openai_base_url: str = _get("OPENAI_BASE_URL", "https://api.openai.com/v1")

    deepseek_api_key: str = _get("DEEPSEEK_API_KEY")
    deepseek_base_url: str = _get("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
    deepseek_model: str = _get("DEEPSEEK_MODEL", "deepseek-chat")

    qwen_api_key: str = _get("QWEN_API_KEY")
    qwen_base_url: str = _get("QWEN_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
    qwen_model: str = _get("QWEN_MODEL", "qwen-plus")

    custom_ai_api_key: str = _get("CUSTOM_AI_API_KEY")
    custom_ai_base_url: str = _get("CUSTOM_AI_BASE_URL")
    custom_ai_model: str = _get("CUSTOM_AI_MODEL")

    # Trading
    trade_enabled: bool = _get_bool("TRADE_ENABLED", False)
    trade_amount_usdc: float = _get_float("TRADE_AMOUNT_USDC", 5.0)
    max_daily_orders: int = _get_int("MAX_DAILY_ORDERS", 3)
    max_daily_spend_usdc: float = _get_float("MAX_DAILY_SPEND_USDC", 15.0)
    max_single_market_position_usdc: float = _get_float("MAX_SINGLE_MARKET_POSITION_USDC", 20.0)

    # Strategy
    scan_interval_seconds: int = _get_int("SCAN_INTERVAL_SECONDS", 60)
    market_limit: int = _get_int("MARKET_LIMIT", 20)
    min_volume_usdc: float = _get_float("MIN_VOLUME_USDC", 50000.0)
    min_ai_probability: float = _get_float("MIN_AI_PROBABILITY", 0.65)
    min_ai_confidence: float = _get_float("MIN_AI_CONFIDENCE", 0.70)
    min_edge: float = _get_float("MIN_EDGE", 0.08)
    max_spread: float = _get_float("MAX_SPREAD", 0.4)

    # Market Filter (本地筛选)
    market_fetch_limit: int = _get_int("MARKET_FETCH_LIMIT", 100)
    market_analyze_limit: int = _get_int("MARKET_ANALYZE_LIMIT", 10)
    min_volume_24h: float = _get_float("MIN_VOLUME_24H", 10000.0)
    min_liquidity: float = _get_float("MIN_LIQUIDITY", 5000.0)
    max_token_spread: float = _get_float("MAX_TOKEN_SPREAD", 0.10)
    max_buy_both_cost: float = _get_float("MAX_BUY_BOTH_COST", 0.15)
    min_mid_price: float = _get_float("MIN_MID_PRICE", 0.05)
    max_mid_price: float = _get_float("MAX_MID_PRICE", 0.95)
    min_days_to_end: float = _get_float("MIN_DAYS_TO_END", 0.0)
    max_days_to_end: float = _get_float("MAX_DAYS_TO_END", 3.0)

    # Market Filter (关键词)
    allow_categories: list = None
    block_keywords: list = None

    # Processed Market Cache
    skip_processed_markets: bool = _get_bool("SKIP_PROCESSED_MARKETS", True)
    recheck_processed_after_hours: float = None
    processed_cache_keep_days: int = _get_int("PROCESSED_CACHE_KEEP_DAYS", 30)

    def __post_init__(self):
        cats = _get("ALLOW_CATEGORIES")
        self.allow_categories = [c.strip() for c in cats.split(",") if c.strip()] if cats else []
        kws = _get("BLOCK_KEYWORDS")
        self.block_keywords = [k.strip().lower() for k in kws.split(",") if k.strip()] if kws else []
        # recheck hours
        raw_recheck = _get("RECHECK_PROCESSED_AFTER_HOURS")
        if raw_recheck:
            self.recheck_processed_after_hours = float(raw_recheck)
        else:
            self.recheck_processed_after_hours = None

    def validate(self):
        """校验必要配置。"""
        errors = []
        if self.trade_enabled and not self.private_key:
            errors.append("TRADE_ENABLED=true 但 POLYMARKET_PRIVATE_KEY 未配置")
        if not self.openai_api_key and self.ai_provider == "openai":
            errors.append("AI_PROVIDER=openai 但 OPENAI_API_KEY 未配置")
        if not self.deepseek_api_key and self.ai_provider == "deepseek":
            errors.append("AI_PROVIDER=deepseek 但 DEEPSEEK_API_KEY 未配置")
        if not self.qwen_api_key and self.ai_provider == "qwen":
            errors.append("AI_PROVIDER=qwen 但 QWEN_API_KEY 未配置")
        if errors:
            raise ValueError("配置校验失败:\n" + "\n".join(errors))


config = Config()
