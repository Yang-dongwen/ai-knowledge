#!/usr/bin/env bash
# 初始化 Halo 站点（若尚未初始化）并写入 HALO_PAT。
# 通过 Docker 网络访问 halo:8090，不依赖宿主机端口。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/deploy/env/app.env}"
NET="${HALO_DOCKER_NET:-auto-exchange-lite_appnet}"
HALO_URL="${HALO_INTERNAL_URL:-http://halo:8090}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "缺少 $ENV_FILE" >&2
  exit 1
fi
sed -i 's/\r$//' "$ENV_FILE" 2>/dev/null || true
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${HALO_ADMIN_USERNAME:=admin}"
: "${HALO_ADMIN_EMAIL:=admin@dwcode.cloud}"
: "${HALO_SITE_TITLE:=dwcode}"
export HALO_ADMIN_USERNAME HALO_ADMIN_PASSWORD HALO_ADMIN_EMAIL HALO_SITE_TITLE

halo_curl() {
  docker run --rm --network "$NET" -v /tmp/halo-probe.body:/tmp/out \
    curlimages/curl:8.5.0 \
    -sS -o /tmp/out -w '%{http_code}' "$@" || echo 000
}

echo "==> wait Halo at $HALO_URL ($NET)"
ok=0
for i in $(seq 1 80); do
  code=$(halo_curl "$HALO_URL/" || echo 000)
  if [[ "$code" != "000" && "$code" != "502" && "$code" != "503" ]]; then
    echo "    http=$code ready (try $i)"
    ok=1
    break
  fi
  echo "    wait $i http=$code"
  sleep 3
done
if [[ "$ok" != "1" ]]; then
  echo "ERROR: Halo 未就绪" >&2
  docker logs --tail 100 "$(docker ps -qf name=halo)" 2>/dev/null || true
  exit 1
fi

echo "==> setup"
SETUP_JSON=$(python3 - <<'PY'
import json, os
print(json.dumps({
  "username": os.environ.get("HALO_ADMIN_USERNAME", "admin"),
  "password": os.environ.get("HALO_ADMIN_PASSWORD", ""),
  "email": os.environ.get("HALO_ADMIN_EMAIL", "admin@dwcode.cloud"),
  "siteTitle": os.environ.get("HALO_SITE_TITLE", "dwcode"),
}, ensure_ascii=False))
PY
)
printf '%s' "$SETUP_JSON" > /tmp/halo-setup.json

for path in \
  /apis/api.console.halo.run/v1alpha1/systemconfigs/-/initialize \
  /apis/console.api.halo.run/v1alpha1/systemconfigs/-/initialize \
  /apis/api.console.halo.run/v1alpha1/system/setup \
  /apis/console.api.system.halo.run/v1alpha1/systemconfigs/-/initialize
do
  code=$(docker run --rm --network "$NET" \
    -v /tmp/halo-setup.json:/tmp/in.json:ro \
    -v /tmp/halo-probe.body:/tmp/out \
    curlimages/curl:8.5.0 \
    -sS -o /tmp/out -w '%{http_code}' \
    -X POST "$HALO_URL$path" -H 'Content-Type: application/json' --data-binary @/tmp/in.json \
    || echo 000)
  echo "    POST $path -> $code"
  if [[ "$code" == "200" || "$code" == "201" || "$code" == "204" || "$code" == "400" || "$code" == "409" ]]; then
    break
  fi
done

echo "==> create PAT"
printf '%s' '{"apiVersion":"security.halo.run/v1alpha1","kind":"PersonalAccessToken","metadata":{"generateName":"pat-","name":""},"spec":{"name":"okx-bot-publish","expiresAt":null,"roles":["super-role"]}}' \
  > /tmp/halo-pat.json
PAT=""
for path in \
  /apis/uc.api.security.halo.run/v1alpha1/personalaccesstokens \
  /apis/api.console.halo.run/v1alpha1/personalaccesstokens \
  /apis/security.halo.run/v1alpha1/personalaccesstokens
do
  code=$(docker run --rm --network "$NET" \
    -v /tmp/halo-pat.json:/tmp/in.json:ro \
    -v /tmp/halo-probe.body:/tmp/out \
    curlimages/curl:8.5.0 \
    -sS -o /tmp/out -w '%{http_code}' \
    -u "${HALO_ADMIN_USERNAME}:${HALO_ADMIN_PASSWORD}" \
    -X POST "$HALO_URL$path" -H 'Content-Type: application/json' --data-binary @/tmp/in.json \
    || echo 000)
  echo "    POST $path -> $code"
  if [[ "$code" == "200" || "$code" == "201" ]]; then
    PAT=$(python3 - <<'PY'
import json
from pathlib import Path
raw = Path("/tmp/halo-probe.body").read_text(encoding="utf-8")
try:
    d = json.loads(raw)
except Exception:
    print("")
    raise SystemExit
for node in (d, d.get("status") or {}, d.get("spec") or {}, (d.get("metadata") or {}).get("annotations") or {}):
    if not isinstance(node, dict):
        continue
    for key in ("token", "value", "security.halo.run/access-token"):
        if node.get(key):
            print(node[key])
            raise SystemExit
print("")
PY
)
    if [[ -n "$PAT" ]]; then
      echo "==> PAT created"
      break
    fi
    echo "    no token in body"
  fi
done

rm -f /tmp/halo-setup.json /tmp/halo-pat.json

if [[ -z "$PAT" ]]; then
  echo "WARN: 自动 PAT 失败。DNS 好后打开 https://blog.dwcode.cloud/console 手动建令牌写入 HALO_PAT" >&2
  exit 0
fi

if grep -q '^HALO_PAT=' "$ENV_FILE"; then
  sed -i "s|^HALO_PAT=.*|HALO_PAT=${PAT}|" "$ENV_FILE"
else
  printf '\nHALO_PAT=%s\n' "$PAT" >> "$ENV_FILE"
fi
sed -i 's|^HALO_ENABLED=.*|HALO_ENABLED=true|' "$ENV_FILE" || true
chmod 600 "$ENV_FILE"
echo "==> wrote HALO_PAT"
echo "DONE."
