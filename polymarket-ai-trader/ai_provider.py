"""
AI 模型模块。
根据配置选择模型供应商，构造 prompt，调用 AI，返回统一 JSON 结果。
支持：openai / deepseek / qwen / custom_openai_compatible
"""

import json
import os
from openai import OpenAI
from config import config
from logger_service import logger

# AI 返回结果的 JSON Schema
AI_RESULT_SCHEMA = {
    "type": "object",
    "required": ["estimated_probability", "confidence", "recommended_side", "should_trade", "reason", "risk_level"],
    "properties": {
        "estimated_probability": {"type": "number", "minimum": 0, "maximum": 1},
        "confidence": {"type": "number", "minimum": 0, "maximum": 1},
        "recommended_side": {"type": "string", "enum": ["YES", "NO", "HOLD"]},
        "should_trade": {"type": "boolean"},
        "reason": {"type": "string"},
        "risk_level": {"type": "string", "enum": ["low", "medium", "high"]}
    }
}

SYSTEM_PROMPT = """You are a professional prediction market analyst. Your job is to estimate the probability of events on Polymarket.

You will be given:
- The market question
- A brief description
- Current YES price (market-implied probability)
- Current NO price
- Market volume

Your task:
1. Estimate the TRUE probability of the event occurring (0.0 to 1.0)
2. Rate your confidence in this estimate (0.0 to 1.0)
3. Recommend a side: YES, NO, or HOLD
4. Decide if this is worth trading (should_trade)
5. Explain your reasoning briefly IN CHINESE (中文)
6. Rate the risk level: low, medium, high

Rules:
- Be calibrated. Don't be overconfident.
- If the market price already reflects fair value, recommend HOLD.
- Only recommend trading if you see a meaningful edge (your estimate differs significantly from market price).
- Consider liquidity, time to resolution, and event clarity.
- If the event is ambiguous, unclear, or too risky, recommend HOLD.

You MUST respond with ONLY a valid JSON object in this exact format:
{
  "question_cn": "市场问题的中文翻译",
  "estimated_probability": 0.68,
  "confidence": 0.74,
  "recommended_side": "YES",
  "should_trade": true,
  "reason": "用中文简要解释你的分析原因",
  "risk_level": "medium"
}

IMPORTANT: Both "reason" and "question_cn" fields MUST be in Chinese (中文).
Do NOT include any text outside the JSON object."""


def _build_user_prompt(market_info: dict) -> str:
    return f"""Analyze this Polymarket event:

Question: {market_info['question']}
Description: {market_info.get('description', 'N/A')}
Current YES price: {market_info['yes_price']:.4f}
Current NO price: {market_info['no_price']:.4f}
Market volume (USDC): {market_info['volume']:,.0f}
End date: {market_info.get('end_date', 'N/A')}

Provide your probability estimate and trading recommendation as JSON.
Remember: "reason" and "question_cn" MUST be in Chinese."""


def _get_client() -> tuple[OpenAI, str]:
    """根据配置返回 OpenAI 兼容客户端和模型名称。"""
    provider = config.ai_provider.lower()

    if provider == "openai":
        client = OpenAI(api_key=config.openai_api_key, base_url=config.openai_base_url)
        model = config.ai_model
    elif provider == "deepseek":
        client = OpenAI(api_key=config.deepseek_api_key, base_url=config.deepseek_base_url)
        model = config.deepseek_model
    elif provider == "qwen":
        client = OpenAI(api_key=config.qwen_api_key, base_url=config.qwen_base_url)
        model = config.qwen_model
    elif provider == "nvidia":
        api_key = os.getenv("NVIDIA_API_KEY", "")
        base_url = os.getenv("NVIDIA_BASE_URL", "https://integrate.api.nvidia.com/v1")
        model_name = os.getenv("NVIDIA_MODEL", "minimaxai/minimax-m2.7")
        client = OpenAI(api_key=api_key, base_url=base_url)
        model = model_name
    elif provider == "custom":
        client = OpenAI(api_key=config.custom_ai_api_key, base_url=config.custom_ai_base_url)
        model = config.custom_ai_model
    else:
        raise ValueError(f"不支持的 AI Provider: {provider}")

    return client, model


def analyze_market(market_info: dict) -> dict | None:
    """
    调用 AI 分析市场，返回统一格式的分析结果。
    如果 AI 调用失败或返回格式不正确，返回 None。
    """
    try:
        client, model = _get_client()
        user_prompt = _build_user_prompt(market_info)

        response = client.chat.completions.create(
            model=model,
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": user_prompt}
            ],
            temperature=config.ai_temperature,
            timeout=config.ai_timeout_seconds
        )

        content = response.choices[0].message.content.strip()

        # 尝试提取 JSON（处理可能的 markdown 代码块）
        if content.startswith("```"):
            lines = content.split("\n")
            json_lines = [l for l in lines if not l.startswith("```")]
            content = "\n".join(json_lines)

        result = json.loads(content)

        # 基础校验
        required_keys = ["estimated_probability", "confidence", "recommended_side", "should_trade", "reason", "risk_level"]
        for key in required_keys:
            if key not in result:
                logger.warning(f"AI 返回缺少字段 {key}: {content[:200]}")
                return None

        # 数值范围校验
        if not (0 <= result["estimated_probability"] <= 1):
            result["estimated_probability"] = max(0, min(1, result["estimated_probability"]))
        if not (0 <= result["confidence"] <= 1):
            result["confidence"] = max(0, min(1, result["confidence"]))
        if result["recommended_side"] not in ("YES", "NO", "HOLD"):
            result["recommended_side"] = "HOLD"

        return result

    except json.JSONDecodeError as e:
        logger.error(f"AI 返回 JSON 解析失败: {e}")
        return None
    except Exception as e:
        logger.error(f"AI 调用异常: {e}")
        return None
