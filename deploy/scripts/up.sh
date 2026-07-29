#!/usr/bin/env bash
# 仅重建/拉起容器（不 git pull）
# 用法（仓库根）: bash deploy/scripts/up.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

ENV_FILE=deploy/env/app.env
if [[ ! -f "$ENV_FILE" && -f deploy/app.env ]]; then
  ENV_FILE=deploy/app.env
elif [[ ! -f "$ENV_FILE" && -f deploy/.env ]]; then
  ENV_FILE=deploy/.env
fi
if [[ ! -f "$ENV_FILE" ]]; then
  echo "缺少 $ENV_FILE ，请先: cp deploy/env/app.env.example deploy/env/app.env 并填写密钥" >&2
  exit 1
fi

COMPOSE="${COMPOSE_FILE:-deploy/stack/compose.lite.yml}"
if [[ ! -f "$COMPOSE" && -f deploy/docker-compose.lite.yml ]]; then
  COMPOSE=deploy/docker-compose.lite.yml
fi

docker compose -f "$COMPOSE" --env-file "$ENV_FILE" up -d --build "$@"
echo ""
echo "已启动。浏览器: http://$(curl -s --connect-timeout 2 ifconfig.me 2>/dev/null || echo '<EC2公网IP>'):8088/"
echo "日志: docker compose -f $COMPOSE --env-file $ENV_FILE logs -f okx-bot"
