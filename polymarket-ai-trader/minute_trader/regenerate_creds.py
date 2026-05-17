"""删除旧 API key，重新注册一个绑定 Deposit Wallet 的"""
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from minute_trader.config import config
from py_clob_client_v2 import ClobClient, ApiCreds

# 第一步：用现有凭证连接，删除旧 key
print("=== 步骤 1: 删除旧 API Key ===")
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

try:
    result = client.delete_api_key()
    print(f"  删除结果: {result}")
except Exception as e:
    print(f"  删除失败（可能已不存在）: {e}")

print()
print("=== 步骤 2: 用 Deposit Wallet 模式重新创建 API Key ===")

# 第二步：用 deposit wallet 模式新建（不传 creds）
client_no_creds = ClobClient(
    host="https://clob.polymarket.com",
    chain_id=137,
    key=config.private_key,
    signature_type=config.signature_type,
    funder=config.funder_address,
)

try:
    new_creds = client_no_creds.create_api_key()
    print("✅ 新 API 凭证创建成功！")
    print()
    print(f"CLOB_API_KEY={new_creds.api_key}")
    print(f"CLOB_SECRET={new_creds.api_secret}")
    print(f"CLOB_PASS_PHRASE={new_creds.api_passphrase}")
    print()
    print("请把以上三个值复制到 .env 文件中替换原来的值。")
except Exception as e:
    print(f"❌ 创建失败: {e}")
    import traceback
    traceback.print_exc()
