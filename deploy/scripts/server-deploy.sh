#!/usr/bin/env bash
# 服务器部署：git pull → docker compose 重建
# 密钥：deploy/app.env（不进 Git）
# 结构：jar 内 application-ec2.yml（变量）+ app.env 注入
set -euo pipefail

APP_DIR="${APP_DIR:-$HOME/auto-exchange}"
ENV_FILE="${ENV_FILE:-$APP_DIR/deploy/app.env}"
if [[ ! -f "$ENV_FILE" && -f "$APP_DIR/deploy/.env" ]]; then
  ENV_FILE="$APP_DIR/deploy/.env"
fi
COMPOSE_FILE="${COMPOSE_FILE:-deploy/docker-compose.lite.yml}"
REF="${REF:-main}"
SKIP_GIT="${SKIP_GIT:-0}"

cd "$APP_DIR"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: 缺少 $ENV_FILE（真实密钥，不提交）" >&2
  exit 1
fi
cp -f "$ENV_FILE" "$APP_DIR/deploy/app.env" 2>/dev/null || true
cp -f "$APP_DIR/deploy/app.env" "$APP_DIR/deploy/.env"
chmod 600 "$APP_DIR/deploy/app.env" "$APP_DIR/deploy/.env" 2>/dev/null || true
sed -i 's/\r$//' "$APP_DIR/deploy/app.env" "$APP_DIR/deploy/.env" 2>/dev/null || true
ENV_FILE="$APP_DIR/deploy/app.env"

if grep -qE 'YOUR_RDS_ENDPOINT|CHANGE_ME' "$ENV_FILE" 2>/dev/null; then
  echo "ERROR: app.env 仍含占位符" >&2
  exit 1
fi

if [[ "$SKIP_GIT" != "1" ]]; then
  if [[ ! -d "$APP_DIR/.git" ]]; then
    echo "ERROR: 不是 git 仓库。先 bootstrap-git 或用 deploy-local.ps1" >&2
    exit 1
  fi
  echo "==> git fetch/reset ($REF)"
  ENV_BAK=$(mktemp)
  cp -a deploy/app.env "$ENV_BAK"
  git fetch --prune origin
  git checkout -f "$REF" 2>/dev/null || git checkout -f -B "$REF" "origin/$REF"
  git reset --hard "origin/$REF"
  git clean -fd -e deploy/app.env -e deploy/.env
  cp -a "$ENV_BAK" deploy/app.env
  cp -a "$ENV_BAK" deploy/.env
  chmod 600 deploy/app.env deploy/.env
  rm -f "$ENV_BAK"
  echo "commit=$(git rev-parse --short HEAD)"
else
  echo "==> SKIP_GIT=1"
fi

if ! docker info >/dev/null 2>&1; then
  echo "ERROR: docker 不可用" >&2
  exit 1
fi

HOST_DATA_ROOT="${HOST_DATA_ROOT:-/data/auto-exchange}"
echo "==> ensure $HOST_DATA_ROOT"
sudo mkdir -p "$HOST_DATA_ROOT/logs" "$HOST_DATA_ROOT/data"
sudo chmod -R a+rwX "$HOST_DATA_ROOT" 2>/dev/null || true

echo "==> docker compose up -d --build"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d --build

echo "==> status"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" ps
for i in 1 2 3 4 5 6 7 8 9 10; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8088/api/ 2>/dev/null || echo 000)
  if [[ "$code" == "401" || "$code" == "200" || "$code" == "403" ]]; then
    echo "health api_http=$code OK"
    break
  fi
  sleep 3
done
echo "DONE."
