"""检查 MarketOrderArgs 的字段定义"""
import inspect
from py_clob_client.clob_types import MarketOrderArgs, OrderArgs

print("=== MarketOrderArgs 字段 ===")
print(inspect.getsource(MarketOrderArgs))
print()

print("=== OrderArgs 字段 ===")
print(inspect.getsource(OrderArgs))
print()

# 看 order_builder 的 build_order
from py_order_utils.builders.order_builder import OrderBuilder
print("=== OrderBuilder.build_order ===")
print(inspect.getsource(OrderBuilder.build_order))
