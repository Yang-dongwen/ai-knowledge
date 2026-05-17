"""
本地已处理市场缓存模块。
用于避免脚本重启后重复分析同一个问题，节省 AI Token。

存储位置：data/processed_markets.json
唯一 key 优先级：condition_id > market_id > question+end_date hash
"""

import os
import json
import hashlib
from datetime import datetime, timezone, timedelta
from typing import Any, Dict, Optional


class ProcessedMarketStore:
    """
    本地已处理市场缓存。
    用于避免脚本重启后重复分析同一个问题。
    """

    def __init__(self, file_path: str = "data/processed_markets.json"):
        self.file_path = file_path
        self.data: Dict[str, Dict[str, Any]] = {}

        os.makedirs(os.path.dirname(file_path), exist_ok=True)
        self.load()

    def load(self) -> None:
        if not os.path.exists(self.file_path):
            self.data = {}
            return

        try:
            with open(self.file_path, "r", encoding="utf-8") as f:
                self.data = json.load(f)
        except Exception:
            self.data = {}

    def save(self) -> None:
        tmp_path = self.file_path + ".tmp"

        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(self.data, f, ensure_ascii=False, indent=2)

        os.replace(tmp_path, self.file_path)

    def get_market_key(self, market: Dict[str, Any]) -> str:
        """
        生成市场唯一 key。
        优先级：condition_id > market_id > question+end_date hash
        """
        condition_id = market.get("condition_id") or market.get("conditionId")
        market_id = market.get("id") or market.get("market_id")

        if condition_id:
            return f"condition:{condition_id}"

        if market_id:
            return f"market:{market_id}"

        question = market.get("question") or market.get("title") or ""
        end_date = market.get("endDate") or market.get("end_date") or ""

        raw = f"{question}|{end_date}"
        return "hash:" + hashlib.sha256(raw.encode("utf-8")).hexdigest()

    def is_processed(
        self,
        market: Dict[str, Any],
        recheck_after_hours: Optional[float] = None,
    ) -> bool:
        """
        判断市场是否已经处理过。

        recheck_after_hours:
        - None：处理过就永远跳过
        - 例如 6：处理过 6 小时后允许重新分析
        """
        key = self.get_market_key(market)

        if key not in self.data:
            return False

        if recheck_after_hours is None:
            return True

        record = self.data[key]
        processed_at = record.get("processed_at")
        if not processed_at:
            return False

        # 永久跳过的状态不受 recheck 影响
        status = record.get("status", "")
        if status in ("blocked_keyword", "unsupported_category", "trade_executed"):
            return True

        try:
            processed_time = datetime.fromisoformat(processed_at)
        except Exception:
            return False

        now = datetime.now(timezone.utc)
        elapsed = now - processed_time

        return elapsed < timedelta(hours=recheck_after_hours)

    def mark_processed(
        self,
        market: Dict[str, Any],
        status: str,
        reason: str = "",
        extra: Optional[Dict[str, Any]] = None,
    ) -> None:
        """
        标记市场为已处理。

        status 建议值：
        - blocked_keyword: 命中屏蔽关键词（永久跳过）
        - unsupported_category: 不支持的类别（永久跳过）
        - too_far_to_end: 距结束太远
        - pre_filter_rejected: 预过滤拒绝
        - orderbook_rejected: 盘口过滤拒绝
        - ai_analyzed_hold: AI 分析完成，推荐 HOLD
        - ai_analyzed_no_edge: AI 分析完成，edge 不足
        - risk_rejected: 风控拒绝
        - trade_signal_dry_run: 模拟盘触发信号
        - trade_executed: 实盘已下单（永久跳过）
        """
        key = self.get_market_key(market)

        question = market.get("question") or market.get("title") or ""

        self.data[key] = {
            "market_key": key,
            "market_id": market.get("id") or market.get("market_id"),
            "condition_id": market.get("condition_id") or market.get("conditionId"),
            "question": question[:200],
            "end_date": market.get("endDate") or market.get("end_date"),
            "status": status,
            "reason": reason,
            "processed_at": datetime.now(timezone.utc).isoformat(),
            "extra": extra or {},
        }

        self.save()

    def cleanup_expired(self, keep_days: int = 30) -> int:
        """
        清理过旧的处理记录，避免文件越来越大。
        """
        now = datetime.now(timezone.utc)
        removed = 0

        keys_to_delete = []

        for key, item in self.data.items():
            processed_at = item.get("processed_at")

            if not processed_at:
                keys_to_delete.append(key)
                continue

            try:
                processed_time = datetime.fromisoformat(processed_at)
            except Exception:
                keys_to_delete.append(key)
                continue

            if now - processed_time > timedelta(days=keep_days):
                keys_to_delete.append(key)

        for key in keys_to_delete:
            del self.data[key]
            removed += 1

        if removed > 0:
            self.save()

        return removed

    def get_stats(self) -> Dict[str, int]:
        """获取缓存统计信息。"""
        stats: Dict[str, int] = {"total": len(self.data)}
        for item in self.data.values():
            status = item.get("status", "unknown")
            stats[status] = stats.get(status, 0) + 1
        return stats
