"""检查 V2 SDK 的 ClobClient 接受哪些参数"""
import inspect
from py_clob_client_v2 import ClobClient

print("=== ClobClient.__init__ 签名 ===")
print(inspect.signature(ClobClient.__init__))
print()
print("=== ClobClient 所有方法 ===")
for name in dir(ClobClient):
    if not name.startswith('_'):
        print(f"  {name}")
