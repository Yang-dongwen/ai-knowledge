"""
日志模块。
记录运行日志、AI 信号、订单结果、异常信息。
使用 JSONL 格式方便后续复盘分析。
"""

import json
import logging
import os
from datetime import datetime, timezone

LOGS_DIR = os.path.join(os.path.dirname(__file__), "logs")
os.makedirs(LOGS_DIR, exist_ok=True)

# 运行日志
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[
        logging.FileHandler(os.path.join(LOGS_DIR, "runtime.log"), encoding="utf-8"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("polymarket-trader")


def _now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _append_jsonl(filename: str, data: dict):
    filepath = os.path.join(LOGS_DIR, filename)
    data["time"] = _now_iso()
    with open(filepath, "a", encoding="utf-8") as f:
        f.write(json.dumps(data, ensure_ascii=False) + "\n")


def log_signal(signal: dict):
    """记录 AI 信号日志。"""
    _append_jsonl("signals.jsonl", signal)


def log_order(order: dict):
    """记录订单日志。"""
    _append_jsonl("orders.jsonl", order)


def log_error(error: dict):
    """记录错误日志。"""
    _append_jsonl("errors.jsonl", error)
