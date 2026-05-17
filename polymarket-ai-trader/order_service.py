"""
下单模块。
初始化 Polymarket CLOB Client，根据信号自动下单。
第一版只支持 BUY YES / BUY NO，使用 FAK 订单类型。
"""

from config import config
from logger_service import logger, log_order, log_error


def place_order(token_id: str, side: str, amount_usdc: float, price: float, market_info: dict) -> dict:
    """
    提交订单到 Polymarket CLOB。

    参数：
        token_id: YES 或 NO 的 token_id
        side: "YES" 或 "NO"
        amount_usdc: 下单金额 (USDC)
        price: 买入价格
        market_info: 市场信息（用于日志）

    返回订单结果字典。
    """
    if not config.trade_enabled:
        result = {
            "market_id": market_info.get("market_id", ""),
            "question": market_info.get("question", ""),
            "side": side,
            "amount_usdc": amount_usdc,
            "price": price,
            "token_id": token_id,
            "order_type": "FAK",
            "status": "dry_run",
            "message": "TRADE_ENABLED=false, 模拟运行"
        }
        log_order(result)
        logger.info(f"[DRY RUN] 模拟下单: {side} @ {price:.4f}, 金额={amount_usdc} USDC")
        return result

    # 实盘下单
    try:
        from py_clob_client.client import ClobClient
        from py_clob_client.clob_types import OrderArgs, OrderType, ApiCreds

        creds = ApiCreds(
            api_key=config.clob_api_key,
            api_secret=config.clob_secret,
            api_passphrase=config.clob_pass_phrase
        ) if config.clob_api_key else None

        client = ClobClient(
            host="https://clob.polymarket.com",
            key=config.private_key,
            chain_id=137,  # Polygon
            funder=config.funder_address,
            signature_type=config.signature_type,
            creds=creds
        )

        # 计算数量：amount_usdc / price
        size = round(amount_usdc / price, 2) if price > 0 else 0

        order_args = OrderArgs(
            token_id=token_id,
            price=price,
            size=size,
            side="BUY",
        )

        resp = client.create_and_post_order(order_args, OrderType.FAK)

        result = {
            "market_id": market_info.get("market_id", ""),
            "question": market_info.get("question", ""),
            "side": side,
            "amount_usdc": amount_usdc,
            "price": price,
            "size": size,
            "token_id": token_id,
            "order_type": "FAK",
            "status": "submitted",
            "order_result": resp if isinstance(resp, dict) else str(resp)
        }
        log_order(result)
        logger.info(f"[LIVE] 订单已提交: {side} @ {price:.4f}, 金额={amount_usdc} USDC")
        return result

    except ImportError:
        error_msg = "py-clob-client 未安装，无法实盘下单"
        logger.error(error_msg)
        error_result = {
            "market_id": market_info.get("market_id", ""),
            "side": side,
            "amount_usdc": amount_usdc,
            "status": "failed",
            "error": error_msg
        }
        log_error(error_result)
        return error_result

    except Exception as e:
        error_msg = f"下单失败: {e}"
        logger.error(error_msg)
        error_result = {
            "market_id": market_info.get("market_id", ""),
            "question": market_info.get("question", ""),
            "side": side,
            "amount_usdc": amount_usdc,
            "price": price,
            "token_id": token_id,
            "status": "failed",
            "error": str(e)
        }
        log_error(error_result)
        log_order(error_result)
        return error_result
