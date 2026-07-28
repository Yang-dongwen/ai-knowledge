#!/usr/bin/env bash
# 服务器部署：可选 git pull → docker compose 重建重启
# 密钥只读本地 env（deploy/app.env），永不进 Git
#
# 用法:
#   bash deploy/scripts/server-deploy.sh
#   REF=main SKIP_GIT=0 bash deploy/scripts/server-deploy.sh
#   SKIP_GIT=1 bash deploy/scripts/server-deploy.sh   # 仅重建（本机 scp 代码后）
#
set -euo pipefail

APP_DIR="${APP_DIR:-$HOME/auto-exchange}"
ENV_FILE="${ENV_FILE:-$APP_DIR/deploy/app.env}"
if [[ ! -f "$ENV_FILE" && -f "$APP_DIR/deploy/.env" ]]; then
  ENV_FILE="$APP_DIR/deploy/.env"
fi
COMPOSE_FILE="${COMPOSE_FILE:-deploy/docker-compose.lite.yml}"
REF="${REF:-main}"
SKIP_GIT="${SKIP_GIT:-0}"
SYNC_ENV_COPY="${SYNC_ENV_COPY:-1}"

cd "$APP_DIR"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: 缺少环境文件: $ENV_FILE" >&2
  echo "请先放置 deploy/app.env（从本机 scp 或 cp app.env.example）" >&2
  exit 1
fi

# 统一 app.env / .env
if [[ "$SYNC_ENV_COPY" == "1" ]]; then
  if [[ "$ENV_FILE" == *app.env ]]; then
    cp -f "$ENV_FILE" "$APP_DIR/deploy/.env"
  else
    cp -f "$ENV_FILE" "$APP_DIR/deploy/app.env"
  fi
  chmod 600 "$APP_DIR/deploy/app.env" "$APP_DIR/deploy/.env" 2>/dev/null || true
  sed -i 's/\r$//' "$APP_DIR/deploy/app.env" "$APP_DIR/deploy/.env" 2>/dev/null || true
  ENV_FILE="$APP_DIR/deploy/app.env"
fi

if grep -qE 'YOUR_RDS_ENDPOINT|CHANGE_ME' "$ENV_FILE" 2>/dev/null; then
  echo "ERROR: $ENV_FILE 仍含占位符 YOUR_RDS_ENDPOINT / CHANGE_ME" >&2
  exit 1
fi

if [[ "$SKIP_GIT" != "1" ]]; then
  if [[ ! -d "$APP_DIR/.git" ]]; then
    echo "ERROR: $APP_DIR 不是 git 仓库。" >&2
    echo "  - 一次性初始化: bash deploy/scripts/bootstrap-git.sh" >&2
    echo "  - 或本机一键同步: pwsh deploy/scripts/deploy-local.ps1" >&2
    echo "  - 仅重建已上传代码: SKIP_GIT=1 bash deploy/scripts/server-deploy.sh" >&2
    exit 1
  fi
  echo "==> git fetch/reset ($REF) — 强制与 origin 一致（保留 deploy/app.env）"
  # 备份密钥（git clean 不删 ignored 文件，再保险一次）
  ENV_BAK=$(mktemp)
  if [[ -f deploy/app.env ]]; then cp -a deploy/app.env "$ENV_BAK"; fi
  git fetch --prune origin
  # 丢弃 scp 残留的未提交改动，避免 pull 冲突
  git checkout -f "$REF" 2>/dev/null || git checkout -f -B "$REF" "origin/$REF"
  git reset --hard "origin/$REF"
  git clean -fd -e deploy/app.env -e deploy/.env
  if [[ -s "$ENV_BAK" ]]; then
    mkdir -p deploy
    cp -a "$ENV_BAK" deploy/app.env
    cp -a "$ENV_BAK" deploy/.env
    chmod 600 deploy/app.env deploy/.env
  fi
  rm -f "$ENV_BAK"
  echo "commit=$(git rev-parse --short HEAD)"
else
  echo "==> SKIP_GIT=1，跳过 git pull"
fi

if ! docker info >/dev/null 2>&1; then
  echo "ERROR: docker 不可用" >&2
  exit 1
fi

echo "==> docker compose up -d --build ($COMPOSE_FILE)"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d --build

echo "==> status"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" ps

# 简单健康探测
for i in 1 2 3 4 5 6 7 8 9 10; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8088/api/ 2>/dev/null || echo 000)
  if [[ "$code" == "401" || "$code" == "200" || "$code" == "403" ]]; then
    echo "health api_http=$code OK"
    break
  fi
  sleep 3
done

echo "DONE."
if [[ -d .git ]]; then
  echo "commit=$(git rev-parse --short HEAD)"
fi
