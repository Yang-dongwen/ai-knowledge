"""
彻底诊断下单问题：打印实际发送的订单 JSON body。
"""
import sys
import os
import json
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from minute_trader.config import config
from py_clob_client_v2 import (
    ClobClient, ApiCreds, MarketOrderArgs, OrderType,
    PartialCreateOrderOptions, Side
)

# 初始化 client
creds = ApiCreds(
    api_key=config.clob_api_key,
    api_secret=config.clob_secret,
    api_passphrase=config.clob_pass_phrase,
)

client = ClobClient(
    host="https://clob.polymarket.com",
    chain_id=137,
    key=config.private_key,
    creds=creds,
    signature_type=config.signature_type,
    funder=config.funder_address,
)

print(f"=== 客户端信息 ===")
print(f"  signer.address() (EOA): {client.signer.address()}")
print(f"  builder.funder: {client.builder.funder}")
print(f"  builder.signature_type: {client.builder.signature_type}")
print(f"  builder._v2_order_signer(): {client.builder._v2_order_signer()}")
print(f"  creds.api_key: {creds.api_key}")
print()

# 用一个真实的 token_id 创建订单（不提交）
# 从最近的日志里拿一个 token_id
token_id = "95087512926585549138487173133434507012502743450051154454470792082870617984337"

# 用 limit order 测试（不需要查询 orderbook）
from py_clob_client_v2 import OrderArgs as LimitOrderArgs

print(f"=== 创建 Limit Order（测试签名）===")
limit_args = LimitOrderArgs(
    token_id=token_id,
    price=0.50,
    size=5,
    side=Side.BUY,
)

options = PartialCreateOrderOptions(tick_size="0.01")

try:
    signed_order = client.create_order(limit_args, options)
    print(f"  签名成功!")
    
    # 打印订单的所有字段
    if hasattr(signed_order, '__dict__'):
        print(f"  order fields:")
        for k, v in signed_order.__dict__.items():
            if k != 'signature':
                print(f"    {k}: {v}")
            else:
                print(f"    signature: {v[:20]}...")
    
    # 看看 order_to_json 输出什么
    print()
    print(f"=== order_to_json 输出 ===")
    from py_clob_client_v2.order_utils.model.order_data_v2 import order_to_json_v2
    body = order_to_json_v2(signed_order, creds.api_key, "GTC", False)
    
    import json
    print(json.dumps(body, indent=2))
    
    print()
    print(f"=== 关键对比 ===")
    print(f"  body.order.signer:       {body['order']['signer']}")
    print(f"  body.order.maker:        {body['order']['maker']}")
    print(f"  body.order.signatureType: {body['order']['signatureType']}")
    print(f"  EOA address:             {client.signer.address()}")
    print(f"  Funder address:          {config.funder_address}")
    
    # 判断
    if body['order']['signer'].lower() == client.signer.address().lower():
        print(f"\n  ✅ signer == EOA，应该和 API key 匹配")
    else:
        print(f"\n  ❌ signer != EOA，这就是问题所在")
    
except Exception as e:
    print(f"  创建失败: {e}")
    import traceback
    traceback.print_exc()
