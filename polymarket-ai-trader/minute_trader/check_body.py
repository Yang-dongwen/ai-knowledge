"""检查 order_to_json 输出的 body 格式"""
import json
import inspect
from py_clob_client.utilities import order_to_json
print("=== order_to_json 源码 ===")
print(inspect.getsource(order_to_json))
