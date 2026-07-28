#!/usr/bin/env bash
# 在 EC2 上执行：连通 RDS 并导入全量 schema
# 用法:
#   export RDS_HOST=xxx.rds.amazonaws.com
#   export DB_USER=admin
#   export DB_PASSWORD='...'
#   export RDS_DATABASE=okx_bot
#   bash deploy/scripts/init-rds.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCHEMA="${ROOT}/okx-bot/src/main/resources/db/schema.sql"

: "${RDS_HOST:?set RDS_HOST}"
: "${DB_USER:?set DB_USER}"
: "${DB_PASSWORD:?set DB_PASSWORD}"
RDS_PORT="${RDS_PORT:-3306}"
RDS_DATABASE="${RDS_DATABASE:-okx_bot}"

if [[ ! -f "$SCHEMA" ]]; then
  echo "schema not found: $SCHEMA" >&2
  exit 1
fi

if ! command -v mysql >/dev/null 2>&1; then
  echo "installing mysql client..."
  sudo apt-get update -y
  sudo apt-get install -y mysql-client
fi

echo "==> ping TCP ${RDS_HOST}:${RDS_PORT}"
timeout 5 bash -c "cat < /dev/null > /dev/tcp/${RDS_HOST}/${RDS_PORT}" \
  && echo "TCP OK" \
  || { echo "TCP FAIL: 检查 RDS 安全组是否放行本机/EC2、是否 Public 或同 VPC"; exit 1; }

echo "==> CREATE DATABASE IF NOT EXISTS ${RDS_DATABASE}"
mysql -h "$RDS_HOST" -P "$RDS_PORT" -u "$DB_USER" -p"$DB_PASSWORD" \
  -e "CREATE DATABASE IF NOT EXISTS \`${RDS_DATABASE}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"

echo "==> import schema.sql"
mysql -h "$RDS_HOST" -P "$RDS_PORT" -u "$DB_USER" -p"$DB_PASSWORD" "$RDS_DATABASE" < "$SCHEMA"

echo "==> tables:"
mysql -h "$RDS_HOST" -P "$RDS_PORT" -u "$DB_USER" -p"$DB_PASSWORD" "$RDS_DATABASE" \
  -e "SHOW TABLES;"

echo "DONE. RDS ready for okx-bot."
