# 历史 SQL 归档（引入 Flyway 前）

自引入 Flyway 起，**新的 DDL/DML 只写**：

```text
okx-bot/src/main/resources/db/migration/
  V1__baseline.sql   # 当前库结构基线（已冻结，勿改）
  V2__描述.sql        # 后续增量，例 V2__kb_note_xxx.sql
```

应用启动时自动 migrate，**不要**再对本目录脚本做 `mysql < xxx.sql` 批量执行。

本目录保留作历史对照与排查参考；其中种子数据（如 `ai_model_config.sql`）仍可在需要时**手工**导入一次。若要把种子也纳入版本管理，请新增 `V{n}__seed_ai_model_config.sql`（用幂等 `INSERT ... ON DUPLICATE KEY UPDATE`）。
