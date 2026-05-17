"""
分钟级交易状态持久化。
防止重启后重复交易同一个市场。
"""

import os
import json
from datetime import datetime, timezone, timedelta


class MinuteStateStore:
    def __init__(self, file_path: str = "data/minute_state.json"):
        self.file_path = file_path
        self.traded_markets: dict = {}

        os.makedirs(os.path.dirname(file_path), exist_ok=True)
        self.load()

    def load(self):
        if not os.path.exists(self.file_path):
            self.traded_markets = {}
            return
        try:
            with open(self.file_path, "r", encoding="utf-8") as f:
                data = json.load(f)
                self.traded_markets = data.get("traded_markets", {})
        except Exception:
            self.traded_markets = {}

    def save(self):
        tmp = self.file_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"traded_markets": self.traded_markets}, f, ensure_ascii=False, indent=2)
        os.replace(tmp, self.file_path)

    def has_traded(self, market_id: str) -> bool:
        """判断该市场是否已经交易过。"""
        return market_id in self.traded_markets

    def mark_traded(self, market_id: str, question: str, side: str, amount: float):
        """标记市场已交易。"""
        self.traded_markets[market_id] = {
            "question": question[:200],
            "side": side,
            "amount_usdc": amount,
            "traded_at": datetime.now(timezone.utc).isoformat(),
        }
        self.save()

    def cleanup_old(self, keep_hours: int = 24):
        """清理超过 N 小时的记录。"""
        now = datetime.now(timezone.utc)
        to_delete = []
        for mid, info in self.traded_markets.items():
            try:
                traded_at = datetime.fromisoformat(info["traded_at"])
                if now - traded_at > timedelta(hours=keep_hours):
                    to_delete.append(mid)
            except:
                to_delete.append(mid)

        for mid in to_delete:
            del self.traded_markets[mid]

        if to_delete:
            self.save()
        return len(to_delete)
