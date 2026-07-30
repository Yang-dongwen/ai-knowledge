-- ============================================================
-- 仅用于「手动建库」。表结构由 Flyway 管理，请勿再靠本文件做增量。
--
-- 迁移目录（真相来源）:
--   src/main/resources/db/migration/
--   V1__baseline.sql  = 当前基线全量结构
--   V2__*.sql 起      = 后续增量（应用启动自动执行）
--
-- 历史增量脚本归档: doc/sql/（引入 Flyway 前手动执行过，勿再对新环境批量套用）
-- ============================================================

CREATE DATABASE IF NOT EXISTS okx_bot DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- 建库后启动 okx-bot，Flyway 会：
--   · 空库：执行 V1 及后续迁移
--   · 已有表、无 flyway_schema_history：baseline 到 V1，仅跑 V2+
