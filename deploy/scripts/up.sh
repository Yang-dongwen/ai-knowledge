#!/usr/bin/env bash
# 在仓库根目录或任意位置: bash deploy/scripts/up.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

ENV_FILE=deploy/app.env
[[ -f "$ENV_FILE" ]] || ENV_FILE=deploy/.env
if [[ ! -f "$ENV_FILE" ]]; then
  echo "缺少 $ENV_FILE ，请先: cp deploy/app.env.example deploy/app.env 并填写 RDS/密钥" >&2
  exit 1
fi

COMPOSE="${COMPOSE_FILE:-deploy/docker-compose.lite.yml}"
docker compose -f "$COMPOSE" --env-file "$ENV_FILE" up -d --build "$@"
echo ""
echo "已启动。浏览器打开: http://$(curl -s --connect-timeout 2 ifconfig.me 2>/dev/null || echo '<EC2公网IP>'):8088/"
echo "日志: docker compose -f $COMPOSE logs -f okx-bot"
