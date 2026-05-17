"""检查 py_clob_client SDK 的实际接口"""
import inspect
from py_clob_client.client import ClobClient
from py_clob_client import clob_types

print("=== ClobClient.create_and_post_order 签名 ===")
method = getattr(ClobClient, 'create_and_post_order', None)
if method:
    print(inspect.signature(method))
    print()

print("=== ClobClient 所有 order 相关方法 ===")
for name in dir(ClobClient):
    if 'order' in name.lower():
        print(f"  {name}")
print()

print("=== clob_types 所有导出 ===")
for name in dir(clob_types):
    if not name.startswith('_'):
        print(f"  {name}")
