#!/usr/bin/env bash
# 服务器部署：git pull → docker compose 重建
# 路径（相对 APP_DIR / 仓库根）：
#   密钥    deploy/env/app.env
#   compose deploy/stack/compose.lite.yml
set -euo pipefail

APP_DIR="${APP_DIR:-$HOME/auto-exchange}"
COMPOSE_FILE="${COMPOSE_FILE:-deploy/stack/compose.lite.yml}"
ENV_REL="deploy/env/app.env"
REF="${REF:-main}"
SKIP_GIT="${SKIP_GIT:-0}"

cd "$APP_DIR"
APP_DIR="$(pwd -P)"

# ---------- 密钥：标准路径 + 旧路径一次性迁移 ----------
mkdir -p deploy/env
if [[ -n "${ENV_FILE:-}" && -f "$ENV_FILE" ]]; then
  :
elif [[ -f "$ENV_REL" ]]; then
  ENV_FILE="$APP_DIR/$ENV_REL"
elif [[ -f deploy/app.env ]]; then
  echo "==> migrate deploy/app.env -> $ENV_REL"
  cp -a deploy/app.env "$ENV_REL"
  ENV_FILE="$APP_DIR/$ENV_REL"
elif [[ -f deploy/.env ]]; then
  echo "==> migrate deploy/.env -> $ENV_REL"
  cp -a deploy/.env "$ENV_REL"
  ENV_FILE="$APP_DIR/$ENV_REL"
else
  echo "ERROR: 缺少 $ENV_REL（真实密钥，不提交）" >&2
  echo "  模板: cp deploy/env/app.env.example deploy/env/app.env" >&2
  exit 1
fi

if [[ "$ENV_FILE" != "$APP_DIR/$ENV_REL" ]]; then
  cp -f "$ENV_FILE" "$APP_DIR/$ENV_REL"
fi
chmod 600 "$APP_DIR/$ENV_REL" 2>/dev/null || true
sed -i 's/\r$//' "$APP_DIR/$ENV_REL" 2>/dev/null || true
ENV_FILE="$APP_DIR/$ENV_REL"

if grep -qE 'YOUR_RDS_ENDPOINT|CHANGE_ME' "$ENV_FILE" 2>/dev/null; then
  echo "ERROR: app.env 仍含占位符" >&2
  exit 1
fi

# ---------- git ----------
if [[ "$SKIP_GIT" != "1" ]]; then
  if [[ ! -d "$APP_DIR/.git" ]]; then
    echo "ERROR: 不是 git 仓库。先 bootstrap-git 或用 deploy/aws/deploy.ps1" >&2
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
  cp -a "$ENV_BAK" "$ENV_REL"
  chmod 600 "$ENV_REL"
  rm -f "$ENV_BAK"
  ENV_FILE="$APP_DIR/$ENV_REL"
  echo "commit=$(git rev-parse --short HEAD)"
else
  echo "==> SKIP_GIT=1"
fi

if ! docker info >/dev/null 2>&1; then
  echo "ERROR: docker 不可用" >&2
  exit 1
fi

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "ERROR: 找不到 compose 文件: $COMPOSE_FILE" >&2
  exit 1
fi

HOST_DATA_ROOT="${HOST_DATA_ROOT:-/data/auto-exchange}"
echo "==> ensure $HOST_DATA_ROOT"
sudo mkdir -p "$HOST_DATA_ROOT/logs" "$HOST_DATA_ROOT/data" "$HOST_DATA_ROOT/halo"
sudo chmod -R a+rwX "$HOST_DATA_ROOT" 2>/dev/null || true

BLOG_COMPOSE="${BLOG_COMPOSE:-deploy/stack/compose.blog.yml}"
COMPOSE_ARGS=(-f "$COMPOSE_FILE")
if [[ -f "$BLOG_COMPOSE" ]]; then
  COMPOSE_ARGS+=(-f "$BLOG_COMPOSE" --profile blog)
  echo "==> halo overlay $BLOG_COMPOSE --profile blog"
fi

# ---------- compose override：绝对路径 env_file（避免相对路径坑）----------
OVERRIDE=$(mktemp /tmp/ae-env.override.XXXXXX.yml)
trap 'rm -f "$OVERRIDE"' EXIT
# YAML: 绝对路径必须原样写入
cat > "$OVERRIDE" <<EOF
services:
  okx-bot:
    env_file:
      - ${ENV_FILE}
EOF
echo "==> override env_file=$ENV_FILE"

echo "==> docker compose ${COMPOSE_ARGS[*]} -f $OVERRIDE up -d --build"
docker compose \
  "${COMPOSE_ARGS[@]}" \
  -f "$OVERRIDE" \
  --env-file "$ENV_FILE" \
  up -d --build

echo "==> status"
docker compose "${COMPOSE_ARGS[@]}" -f "$OVERRIDE" --env-file "$ENV_FILE" ps
for i in 1 2 3 4 5 6 7 8 9 10; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8088/api/ 2>/dev/null || echo 000)
  if [[ "$code" == "401" || "$code" == "200" || "$code" == "403" ]]; then
    echo "health api_http=$code OK"
    break
  fi
  sleep 3
done
echo "DONE."
