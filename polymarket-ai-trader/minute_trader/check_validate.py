"""检查 _validate_inputs 的逻辑"""
import inspect
from py_order_utils.builders.order_builder import OrderBuilder

print("=== OrderBuilder._validate_inputs ===")
print(inspect.getsource(OrderBuilder._validate_inputs))
