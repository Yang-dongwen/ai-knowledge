"""
分钟级 AI 分析模块。
输入短线特征 + Polymarket 盘口，输出 Up/Down 概率判断。
"""

import os
import json
from openai import OpenAI
from minute_trader.config import config
from logger_service import logger

SYSTEM_PROMPT = """你是一个加密货币短线交易分析师，专门分析 Polymarket 上的分钟级 Up/Down 市场。

你会收到：
- 市场问题（如 "Bitcoin 5 Minute Up or Down"）
- 当前加密货币实时价格
- 过去 1/3/5 分钟的价格变化
- 短线动量方向
- 波动率
- Polymarket Up/Down 当前盘口价格
- 距离结算的剩余秒数

你的任务：
1. 估计 Up（上涨）的真实概率（0.0 到 1.0）
2. 给出你的置信度（0.0 到 1.0）
3. 推荐方向：UP、DOWN 或 HOLD
4. 是否值得交易
5. 用中文简要解释原因

规则：
- 短线交易要看动量和趋势延续性
- 如果价格已经大幅偏离且接近结算，趋势可能延续
- 如果波动率很低且无明显方向，推荐 HOLD
- 如果 Polymarket 价格已经反映了当前趋势（edge 很小），推荐 HOLD
- 距离结算时间越短，当前趋势延续的概率越高
- 不要过度自信，短线预测本身不确定性很高

你必须只返回一个 JSON 对象：
{
  "estimated_up_probability": 0.62,
  "confidence": 0.71,
  "recommended_side": "UP",
  "should_trade": true,
  "reason": "中文解释"
}

不要在 JSON 外面写任何文字。"""


def _build_prompt(market_info: dict, features: dict) -> str:
    return f"""分析这个 Polymarket 分钟级市场：

市场问题: {market_info['question']}
距离结算: {market_info['seconds_to_close']} 秒

当前 {features['symbol']} 价格: {features['current_price']:.2f} USDT
过去 1 分钟变化: {features['last_1m_change_pct']:.4f}%
过去 3 分钟变化: {features['last_3m_change_pct']:.4f}%
过去 5 分钟变化: {features['last_5m_change_pct']:.4f}%
波动率: {features['volatility_pct']:.4f}%
动量方向: {features['momentum']}（过去5根1m K线中 {features['up_candles_5']} 根阳线）

Polymarket 盘口:
UP 价格: bid={market_info['up_bid']:.4f} ask={market_info['up_ask']:.4f} spread={market_info['up_spread']:.4f}
DOWN 价格: bid={market_info['down_bid']:.4f} ask={market_info['down_ask']:.4f} spread={market_info['down_spread']:.4f}

请给出你的 Up 概率估计和交易建议（JSON 格式）。"""


def _get_client() -> tuple:
    """获取 AI 客户端。"""
    provider = config.ai_provider.lower()

    if provider == "nvidia":
        api_key = os.getenv("NVIDIA_API_KEY", "")
        base_url = os.getenv("NVIDIA_BASE_URL", "https://integrate.api.nvidia.com/v1")
        model = os.getenv("NVIDIA_MODEL", "")
        return OpenAI(api_key=api_key, base_url=base_url), model
    elif provider == "openai":
        return OpenAI(api_key=os.getenv("OPENAI_API_KEY"), base_url=os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1")), config.ai_model
    elif provider == "deepseek":
        return OpenAI(api_key=os.getenv("DEEPSEEK_API_KEY"), base_url=os.getenv("DEEPSEEK_BASE_URL")), os.getenv("DEEPSEEK_MODEL", "deepseek-chat")
    elif provider == "custom":
        return OpenAI(api_key=os.getenv("CUSTOM_AI_API_KEY"), base_url=os.getenv("CUSTOM_AI_BASE_URL")), os.getenv("CUSTOM_AI_MODEL", "")
    else:
        raise ValueError(f"不支持的 AI Provider: {provider}")


def analyze_minute_market(market_info: dict, features: dict) -> dict | None:
    """
    调用 AI 分析分钟级市场。
    返回 {estimated_up_probability, confidence, recommended_side, should_trade, reason}
    """
    try:
        client, model = _get_client()
        prompt = _build_prompt(market_info, features)

        response = client.chat.completions.create(
            model=model,
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": prompt}
            ],
            temperature=config.ai_temperature,
            timeout=config.ai_timeout_seconds
        )

        content = response.choices[0].message.content.strip()

        # 处理 markdown 代码块
        if content.startswith("```"):
            lines = content.split("\n")
            content = "\n".join(l for l in lines if not l.startswith("```"))

        result = json.loads(content)

        # 严格校验
        result = validate_ai_result(result)
        if not result:
            logger.warning(f"AI 返回格式不合法: {content[:200]}")
            return None

        return result

    except json.JSONDecodeError as e:
        logger.error(f"AI 返回 JSON 解析失败: {e}")
        return None
    except Exception as e:
        logger.error(f"AI 调用异常: {e}")
        return None


def validate_ai_result(result: dict) -> dict | None:
    """严格校验 AI 返回结果。"""
    try:
        up_prob = float(result.get("estimated_up_probability", -1))
        confidence = float(result.get("confidence", -1))
        side = str(result.get("recommended_side", "")).upper()
        raw_should_trade = result.get("should_trade", False)
        reason = str(result.get("reason", ""))[:300]
    except (TypeError, ValueError):
        return None

    if up_prob < 0 or up_prob > 1:
        return None
    if confidence < 0 or confidence > 1:
        return None
    if side not in ("UP", "DOWN", "HOLD"):
        side = "HOLD"

    # 修复 bool("false") == True 的问题
    if isinstance(raw_should_trade, bool):
        should_trade = raw_should_trade
    elif isinstance(raw_should_trade, str):
        should_trade = raw_should_trade.strip().lower() in ("true", "1", "yes")
    else:
        should_trade = False

    if side == "HOLD":
        should_trade = False

    return {
        "estimated_up_probability": up_prob,
        "confidence": confidence,
        "recommended_side": side,
        "should_trade": should_trade,
        "reason": reason,
    }
