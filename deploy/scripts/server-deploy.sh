#!/usr/bin/env bash
# 服务器部署：git pull → docker compose 重建
# 密钥：deploy/env/app.env（不进 Git）
# 结构：jar 内 application-ec2.yml（变量）+ app.env 注入
set -euo pipefail

APP_DIR="${APP_DIR:-$HOME/auto-exchange}"
COMPOSE_FILE="${COMPOSE_FILE:-deploy/stack/compose.lite.yml}"
REF="${REF:-main}"
SKIP_GIT="${SKIP_GIT:-0}"

cd "$APP_DIR"

# ---------- 解析 / 迁移密钥文件 ----------
# 新路径 deploy/env/app.env；兼容旧 deploy/app.env、deploy/.env
mkdir -p deploy/env
ENV_FILE="${ENV_FILE:-}"
if [[ -z "$ENV_FILE" ]]; then
  if [[ -f deploy/env/app.env ]]; then
    ENV_FILE="$APP_DIR/deploy/env/app.env"
  elif [[ -f deploy/app.env ]]; then
    echo "==> migrate deploy/app.env -> deploy/env/app.env"
    cp -a deploy/app.env deploy/env/app.env
    ENV_FILE="$APP_DIR/deploy/env/app.env"
  elif [[ -f deploy/.env ]]; then
    echo "==> migrate deploy/.env -> deploy/env/app.env"
    cp -a deploy/.env deploy/env/app.env
    ENV_FILE="$APP_DIR/deploy/env/app.env"
  fi
fi

if [[ -z "${ENV_FILE:-}" || ! -f "$ENV_FILE" ]]; then
  echo "ERROR: 缺少 deploy/env/app.env（真实密钥，不提交）" >&2
  echo "  模板: cp deploy/env/app.env.example deploy/env/app.env" >&2
  exit 1
fi

# 统一到标准路径
if [[ "$ENV_FILE" != "$APP_DIR/deploy/env/app.env" ]]; then
  cp -f "$ENV_FILE" "$APP_DIR/deploy/env/app.env"
fi
# 兼容旧 compose/脚本读 deploy/.env
cp -f "$APP_DIR/deploy/env/app.env" "$APP_DIR/deploy/env/.env" 2>/dev/null || true
chmod 600 "$APP_DIR/deploy/env/app.env" 2>/dev/null || true
sed -i 's/\r$//' "$APP_DIR/deploy/env/app.env" 2>/dev/null || true
ENV_FILE="$APP_DIR/deploy/env/app.env"

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
  cp -a "$ENV_FILE" "$ENV_BAK"
  git fetch --prune origin
  git checkout -f "$REF" 2>/dev/null || git checkout -f -B "$REF" "origin/$REF"
  git reset --hard "origin/$REF"
  git clean -fd \
    -e deploy/env/app.env \
    -e deploy/env/.env \
    -e deploy/env/app.env.local \
    -e deploy/env/SECRETS_INVENTORY.env \
    -e deploy/app.env \
    -e deploy/.env
  mkdir -p deploy/env
  cp -a "$ENV_BAK" deploy/env/app.env
  chmod 600 deploy/env/app.env
  rm -f "$ENV_BAK"
  ENV_FILE="$APP_DIR/deploy/env/app.env"
  echo "commit=$(git rev-parse --short HEAD)"
else
  echo "==> SKIP_GIT=1"
fi

if ! docker info >/dev/null 2>&1; then
  echo "ERROR: docker 不可用" >&2
  exit 1
fi

# 兼容旧默认 compose 路径
if [[ ! -f "$COMPOSE_FILE" && -f deploy/docker-compose.lite.yml ]]; then
  COMPOSE_FILE=deploy/docker-compose.lite.yml
fi
if [[ ! -f "$COMPOSE_FILE" && -f deploy/stack/compose.lite.yml ]]; then
  COMPOSE_FILE=deploy/stack/compose.lite.yml
fi

HOST_DATA_ROOT="${HOST_DATA_ROOT:-/data/auto-exchange}"
echo "==> ensure $HOST_DATA_ROOT"
sudo mkdir -p "$HOST_DATA_ROOT/logs" "$HOST_DATA_ROOT/data"
sudo chmod -R a+rwX "$HOST_DATA_ROOT" 2>/dev/null || true

echo "==> docker compose -f $COMPOSE_FILE up -d --build"
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
