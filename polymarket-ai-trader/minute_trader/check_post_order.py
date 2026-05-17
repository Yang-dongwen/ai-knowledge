"""检查 post_order 的签名和实现"""
import inspect
from py_clob_client.client import ClobClient

print("=== post_order 签名 ===")
print(inspect.signature(ClobClient.post_order))
print()
print("=== post_order 源码 ===")
print(inspect.getsource(ClobClient.post_order))
