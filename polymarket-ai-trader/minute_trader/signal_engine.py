"""
程序化预信号模块。
在调用 AI 之前，先用程序判断是否有明显方向。
只有预信号通过才调用 AI，减少无效 AI 调用。
"""


def should_call_ai(features: dict, market_info: dict, config) -> tuple[bool, str, str]:
    """
    判断是否值得调用 AI。
    返回 (是否调用, 原因, 预判方向)

    逻辑：
    - 动量不明确 → 不调用
    - 短线波动太小 → 不调用
    - 盘口 spread 过大 → 不调用
    - 有明确方向才调用
    """
    momentum = features.get("momentum", "NEUTRAL")
    change_1m = abs(features.get("last_1m_change_pct", 0))
    change_3m = abs(features.get("last_3m_change_pct", 0))

    # 动量不明确
    if momentum == "NEUTRAL":
        # 但如果 3m 变化较大，仍然可以调用
        if change_3m < 0.05:
            return False, "动量不明确且波动小", "HOLD"

    # 短线波动太小（没有方向性）
    if change_1m < 0.01 and change_3m < 0.03:
        return False, "短线波动太小，无明显方向", "HOLD"

    # 盘口 spread 过大
    if market_info.get("up_spread", 1) > config.max_token_spread:
        return False, f"UP spread 过大: {market_info['up_spread']:.4f}", "HOLD"
    if market_info.get("down_spread", 1) > config.max_token_spread:
        return False, f"DOWN spread 过大: {market_info['down_spread']:.4f}", "HOLD"

    # 判断预信号方向
    change_5m = features.get("last_5m_change_pct", 0)

    if change_5m > 0.02 and momentum == "UP":
        pre_side = "UP"
    elif change_5m < -0.02 and momentum == "DOWN":
        pre_side = "DOWN"
    elif change_3m > 0.04:
        pre_side = "UP"
    elif change_3m < -0.04:
        pre_side = "DOWN"
    else:
        return False, "方向不明确，不调用AI", "HOLD"

    return True, "有方向信号", pre_side
