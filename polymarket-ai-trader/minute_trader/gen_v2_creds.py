"""为 V2 + Deposit Wallet 派生新的 API 凭证"""
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from minute_trader.config import config
from py_clob_client_v2 import ClobClient

print("正在派生 V2 API 凭证...")
print(f"  EOA 私钥地址 → 你的钱包")
print(f"  Funder (Deposit Wallet): {config.funder_address}")
print(f"  Signature Type: {config.signature_type}")
print()

# 用 deposit wallet 模式初始化 client
client = ClobClient(
    host="https://clob.polymarket.com",
    chain_id=137,
    key=config.private_key,
    signature_type=config.signature_type,
    funder=config.funder_address,
)

print(f"  Client signer address: {client.get_address()}")
print()

# 派生新的 API 凭证
try:
    creds = client.create_or_derive_api_key()
    print("✅ V2 API 凭证生成成功！")
    print()
    print(f"CLOB_API_KEY={creds.api_key}")
    print(f"CLOB_SECRET={creds.api_secret}")
    print(f"CLOB_PASS_PHRASE={creds.api_passphrase}")
    print()
    print("请把以上三个值复制到你的 .env 文件中替换原来的值。")
except Exception as e:
    print(f"❌ 派生失败: {e}")
    import traceback
    traceback.print_exc()
