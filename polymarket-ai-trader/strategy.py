"""
策略模块。
根据 AI 分析结果和市场价格计算 edge，判断交易方向。

Edge 计算使用 best_ask（实际买入价），因为下单时吃的是 ask。
AI 分析时用 mid（参考价格），但 edge 和下单用 ask。
"""

from config import config


def calculate_edge(ai_result: dict, market_info: dict) -> dict:
    """
    计算 AI 预测概率与实际买入价格之间的 edge。

    逻辑：
    - 如果 AI 推荐 YES：edge = AI估计YES概率 - YES best_ask
    - 如果 AI 推荐 NO：edge = AI估计NO概率 - NO best_ask
      其中 AI估计NO概率 = 1 - AI估计YES概率

    使用 best_ask 是因为买入时实际吃的是卖单价格。
    """
    ai_prob = ai_result["estimated_probability"]
    recommended_side = ai_result["recommended_side"]

    if recommended_side == "YES":
        # 买入 YES：用 yes_ask 或 yes_buy_price
        buy_price = market_info.get("yes_buy_price", market_info.get("yes_ask", market_info.get("yes_price", 0)))
        edge = ai_prob - buy_price
        token_id = market_info["yes_token_id"]
    elif recommended_side == "NO":
        # 买入 NO：用 no_ask 或 no_buy_price
        no_prob = 1.0 - ai_prob
        buy_price = market_info.get("no_buy_price", market_info.get("no_ask", market_info.get("no_price", 0)))
        edge = no_prob - buy_price
        token_id = market_info["no_token_id"]
    else:
        return {
            "side": "HOLD",
            "edge": 0,
            "buy_price": 0,
            "token_id": "",
            "should_trade": False,
            "reason": "AI 推荐 HOLD"
        }

    return {
        "side": recommended_side,
        "edge": round(edge, 4),
        "buy_price": buy_price,
        "token_id": token_id,
        "should_trade": edge >= config.min_edge,
        "reason": f"edge={edge:.4f}, buy_price={buy_price:.4f}, min_edge={config.min_edge}"
    }
