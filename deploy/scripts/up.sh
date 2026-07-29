#!/usr/bin/env bash
# 仅重建/拉起容器（不 git pull）
# 用法（仓库根）: bash deploy/scripts/up.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

ENV_REL="deploy/env/app.env"
COMPOSE_REL="${COMPOSE_FILE:-deploy/stack/compose.lite.yml}"

if [[ ! -f "$ENV_REL" ]]; then
  if [[ -f deploy/app.env ]]; then
    mkdir -p deploy/env
    cp -a deploy/app.env "$ENV_REL"
    echo "==> migrated deploy/app.env -> $ENV_REL"
  elif [[ -f deploy/.env ]]; then
    mkdir -p deploy/env
    cp -a deploy/.env "$ENV_REL"
    echo "==> migrated deploy/.env -> $ENV_REL"
  else
    echo "缺少 $ENV_REL ，请先: cp deploy/env/app.env.example deploy/env/app.env" >&2
    exit 1
  fi
fi

if [[ ! -f "$COMPOSE_REL" ]]; then
  echo "缺少 $COMPOSE_REL" >&2
  exit 1
fi

# 绝对路径注入 compose 的 env_file（见 stack/compose.*.yml）
export APP_ENV_FILE="$ROOT/$ENV_REL"

docker compose \
  -f "$COMPOSE_REL" \
  --env-file "$ENV_REL" \
  up -d --build "$@"

echo ""
echo "已启动。浏览器: http://$(curl -s --connect-timeout 2 ifconfig.me 2>/dev/null || echo '<EC2公网IP>'):8088/"
echo "日志: APP_ENV_FILE=$APP_ENV_FILE docker compose -f $COMPOSE_REL --env-file $ENV_REL logs -f okx-bot"
