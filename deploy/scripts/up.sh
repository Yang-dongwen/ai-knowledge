#!/usr/bin/env bash
# 在仓库根目录或任意位置: bash deploy/scripts/up.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

if [[ ! -f deploy/.env ]]; then
  echo "缺少 deploy/.env ，请先: cp deploy/.env.example deploy/.env 并填写 RDS/密钥" >&2
  exit 1
fi

docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build "$@"
echo ""
echo "已启动。浏览器打开: http://$(curl -s --connect-timeout 2 ifconfig.me 2>/dev/null || echo '<EC2公网IP>')/"
echo "日志: docker compose -f deploy/docker-compose.yml logs -f okx-bot"
