"""检查 deposit wallet 状态"""
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from minute_trader.config import config
from py_clob_client_v2 import ClobClient, ApiCreds

print(f"EOA 地址: 由私钥派生")
print(f"Deposit Wallet (funder): {config.funder_address}")
print(f"Signature Type: {config.signature_type}")
print()

creds = ApiCreds(
    api_key=config.clob_api_key,
    api_secret=config.clob_secret,
    api_passphrase=config.clob_pass_phrase,
)

# 用 deposit wallet 模式
client = ClobClient(
    host="https://clob.polymarket.com",
    chain_id=137,
    key=config.private_key,
    creds=creds,
    signature_type=config.signature_type,
    funder=config.funder_address,
)

print(f"Client signer address (EOA): {client.get_address()}")
print()

# 查询余额
try:
    print("查询 USDC 余额（COLLATERAL）...")
    balance = client.get_balance_allowance({"asset_type": "COLLATERAL"})
    print(f"  余额: {balance}")
except Exception as e:
    print(f"  查询失败: {e}")

print()

# 查询 API keys
try:
    print("查询当前 API keys...")
    keys = client.get_api_keys()
    print(f"  API keys: {keys}")
except Exception as e:
    print(f"  查询失败: {e}")
