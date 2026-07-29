#!/usr/bin/env bash
# Cloudflare Quick Tunnel：免费 https://*.trycloudflare.com，无需域名
#
# 用法:
#   bash deploy/scripts/quick-tunnel.sh start
#   bash deploy/scripts/quick-tunnel.sh url
#   bash deploy/scripts/quick-tunnel.sh stop
#   bash deploy/scripts/quick-tunnel.sh restart
#   bash deploy/scripts/quick-tunnel.sh logs
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

COMPOSE_REL="${COMPOSE_FILE:-deploy/stack/compose.lite.yml}"
ENV_REL="deploy/env/app.env"
[[ -f "$ENV_REL" ]] || ENV_REL="deploy/.env"

compose() {
  # Quick：不挂 named 配置，避免误用固定隧道
  local args=(-f "$COMPOSE_REL" --profile tunnel)
  [[ -f "$ENV_REL" ]] && args+=(--env-file "$ENV_REL")
  # 若存在 named override，临时不用它（quick 用 compose 默认 --url）
  docker compose "${args[@]}" "$@"
}

extract_url() {
  compose logs tunnel 2>/dev/null \
    | grep -oE 'https://[a-zA-Z0-9-]+\.trycloudflare\.com' \
    | tail -1 || true
}

save_url() {
  local url="$1"
  mkdir -p deploy/env 2>/dev/null || true
  echo "$url" > deploy/env/quick-tunnel.url 2>/dev/null || true
  if [[ -d /data/auto-exchange ]]; then
    echo "$url" | sudo tee /data/auto-exchange/quick-tunnel.url >/dev/null 2>&1 || true
  fi
}

wait_url() {
  local i=0 url=""
  echo "==> 等待 trycloudflare.com（最多 90s）..."
  while [[ $i -lt 45 ]]; do
    url=$(extract_url)
    if [[ -n "$url" ]]; then
      save_url "$url"
      echo ""
      echo "=========================================="
      echo "  公网地址（Quick Tunnel）"
      echo "  $url"
      echo "=========================================="
      echo ""
      echo "提示:"
      echo "  - 临时隧道：tunnel 容器重建后 URL 会变"
      echo "  - 查看: bash deploy/scripts/quick-tunnel.sh url"
      echo "  - 支付回调等可把 PAY_PUBLIC_BASE_URL 改成该 https 地址后 sync-env"
      return 0
    fi
    sleep 2
    i=$((i + 1))
  done
  echo "ERROR: 未解析到 URL，执行: bash deploy/scripts/quick-tunnel.sh logs" >&2
  compose ps tunnel || true
  return 1
}

cmd="${1:-url}"
case "$cmd" in
  start|up)
    echo "==> 启动 Cloudflare Quick Tunnel"
    # 确保 web 已在跑
    if ! docker compose -f "$COMPOSE_REL" ps --status running web 2>/dev/null | grep -q web; then
      echo "WARN: web 未运行，先确保: bash deploy/scripts/server-deploy.sh" >&2
    fi
    # --no-deps：只启 tunnel，避免误重建 okx-bot（丢掉 env override）
    compose up -d --no-deps tunnel
    wait_url
    ;;
  stop|down)
    compose stop tunnel 2>/dev/null || true
    compose rm -f tunnel 2>/dev/null || true
    echo "tunnel stopped"
    ;;
  restart)
    compose up -d --no-deps --force-recreate tunnel
    wait_url
    ;;
  url|status)
    url=$(extract_url)
    if [[ -z "$url" && -f /data/auto-exchange/quick-tunnel.url ]]; then
      url=$(cat /data/auto-exchange/quick-tunnel.url)
    fi
    if [[ -z "$url" && -f deploy/env/quick-tunnel.url ]]; then
      url=$(cat deploy/env/quick-tunnel.url)
    fi
    if [[ -n "$url" ]]; then
      echo "$url"
      compose ps tunnel 2>/dev/null || true
    else
      echo "尚无 URL。请: bash deploy/scripts/quick-tunnel.sh start" >&2
      exit 1
    fi
    ;;
  logs)
    compose logs -f --tail=80 tunnel
    ;;
  *)
    echo "用法: $0 {start|stop|restart|url|logs}" >&2
    exit 1
    ;;
esac
