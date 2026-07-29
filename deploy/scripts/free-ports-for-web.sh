#!/usr/bin/env bash
# 把 xray 占用的 80/443 改到高位端口，腾给 Caddy 网站
# 新端口: 18443 (原443), 18080 (原80)
set -euo pipefail

CFG=/usr/local/etc/xray/config.json
BAK="/usr/local/etc/xray/config.json.bak.$(date +%Y%m%d%H%M%S)"

if [[ ! -f "$CFG" ]]; then
  echo "no xray config at $CFG ，跳过"
  exit 0
fi

if ! command -v python3 >/dev/null; then
  echo "need python3" >&2
  exit 1
fi

echo "==> backup $CFG -> $BAK"
sudo cp -a "$CFG" "$BAK"

sudo python3 - <<'PY'
import json
from pathlib import Path
p = Path("/usr/local/etc/xray/config.json")
c = json.loads(p.read_text())
changed = False
mapping = {443: 18443, 80: 18080}
for ib in c.get("inbounds", []):
    port = ib.get("port")
    if port in mapping:
        newp = mapping[port]
        print(f"  inbound {ib.get('tag') or ib.get('protocol')}: {port} -> {newp}")
        ib["port"] = newp
        changed = True
if not changed:
    print("  no 80/443 inbounds found (already moved?)")
else:
    p.write_text(json.dumps(c, indent=2, ensure_ascii=False) + "\n")
    print("  config written")
PY

if systemctl is-active --quiet xray 2>/dev/null; then
  echo "==> restart xray"
  sudo systemctl restart xray
  sleep 1
  systemctl is-active xray && echo "xray: active"
else
  echo "xray service not active"
fi

echo "==> listening ports after change:"
sudo ss -lntp | grep -E ':80|:443|:18080|:18443' || true
echo "DONE. website can bind 80/443; xray SS now on 18080/18443"
