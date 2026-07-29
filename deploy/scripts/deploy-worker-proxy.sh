#!/usr/bin/env bash
# 部署 Worker 反代到 *.workers.dev
# 需要本机已: npm + wrangler 登录（npx wrangler login）或 CLOUDFLARE_API_TOKEN
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT/deploy/worker-proxy"

if [[ -n "${CLOUDFLARE_API_TOKEN:-}" ]]; then
  echo "==> using CLOUDFLARE_API_TOKEN"
elif npx --yes wrangler@4 whoami >/dev/null 2>&1; then
  echo "==> wrangler session ok"
else
  echo "请先登录 Cloudflare:"
  echo "  npx wrangler login"
  echo "或设置环境变量 CLOUDFLARE_API_TOKEN（Workers Edit 权限）"
  exit 1
fi

npx --yes wrangler@4 deploy
echo ""
echo "OK: https://shrill-dew-d53a.dwcode.workers.dev"
echo "建议: PAY_PUBLIC_BASE_URL=https://shrill-dew-d53a.dwcode.workers.dev"
