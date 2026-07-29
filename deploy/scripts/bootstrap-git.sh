#!/usr/bin/env bash
# 一次性：tar 目录 → git clone，保留 deploy/env/app.env
#
# 用法:
#   bash deploy/scripts/bootstrap-git.sh
#   REPO=git@github.com:Yang-dongwen/auto-exchange.git bash deploy/scripts/bootstrap-git.sh
#
set -euo pipefail

APP_DIR="${APP_DIR:-$HOME/auto-exchange}"
REPO="${REPO:-git@github.com:Yang-dongwen/auto-exchange.git}"
BRANCH="${BRANCH:-main}"
KEY_FILE="${KEY_FILE:-$HOME/.ssh/github_deploy}"
ENV_REL="deploy/env/app.env"

echo "==> ensure deploy key"
if [[ ! -f "$KEY_FILE" ]]; then
  ssh-keygen -t ed25519 -C "ec2-auto-exchange-deploy" -f "$KEY_FILE" -N ""
fi
chmod 600 "$KEY_FILE"
mkdir -p "$HOME/.ssh"
chmod 700 "$HOME/.ssh"

if ! grep -q 'Host github.com' "$HOME/.ssh/config" 2>/dev/null; then
  cat >> "$HOME/.ssh/config" <<EOF
Host github.com
  HostName github.com
  User git
  IdentityFile $KEY_FILE
  IdentitiesOnly yes
EOF
  chmod 600 "$HOME/.ssh/config"
fi

echo
echo "=========================================="
echo " 请把下面【公钥】加到 GitHub 仓库 Deploy keys（只读）："
echo " Settings → Deploy keys → Add deploy key"
echo "=========================================="
cat "${KEY_FILE}.pub"
echo "=========================================="
echo

set +e
gh_out=$(ssh -o StrictHostKeyChecking=accept-new -o BatchMode=yes -T git@github.com 2>&1)
set -e
echo "github_ssh: $gh_out"
if ! echo "$gh_out" | grep -qiE 'successfully authenticated|Hi '; then
  echo "WARN: 尚未能访问 GitHub。请先添加 Deploy Key 后重新执行本脚本。"
  exit 2
fi

# 备份密钥（新路径优先）
ENV_BAK=$(mktemp)
if [[ -f "$APP_DIR/$ENV_REL" ]]; then
  cp -a "$APP_DIR/$ENV_REL" "$ENV_BAK"
  echo "backed up $ENV_REL"
elif [[ -f "$APP_DIR/deploy/app.env" ]]; then
  cp -a "$APP_DIR/deploy/app.env" "$ENV_BAK"
  echo "backed up deploy/app.env (legacy)"
elif [[ -f "$APP_DIR/deploy/.env" ]]; then
  cp -a "$APP_DIR/deploy/.env" "$ENV_BAK"
  echo "backed up deploy/.env (legacy)"
else
  echo "ERROR: 未找到 $APP_DIR/$ENV_REL，中止以免丢密钥" >&2
  exit 1
fi

NEW_DIR="${APP_DIR}.gitnew"
OLD_DIR="${APP_DIR}.pre-git.$(date +%Y%m%d%H%M%S)"
rm -rf "$NEW_DIR"
echo "==> git clone $REPO ($BRANCH)"
git clone --branch "$BRANCH" --depth 1 "$REPO" "$NEW_DIR"

echo "==> restore secrets -> $ENV_REL"
mkdir -p "$NEW_DIR/deploy/env"
cp -a "$ENV_BAK" "$NEW_DIR/$ENV_REL"
chmod 600 "$NEW_DIR/$ENV_REL"
chmod +x "$NEW_DIR/deploy/scripts/"*.sh 2>/dev/null || true

echo "==> swap directories"
mv "$APP_DIR" "$OLD_DIR"
mv "$NEW_DIR" "$APP_DIR"
rm -f "$ENV_BAK"

mkdir -p "$HOME/bin"
ln -sfn "$APP_DIR/deploy/scripts/server-deploy.sh" "$HOME/bin/deploy"
ln -sfn "$APP_DIR/deploy/scripts/up.sh" "$HOME/bin/up"
chmod +x "$APP_DIR/deploy/scripts/server-deploy.sh" "$APP_DIR/deploy/scripts/up.sh" 2>/dev/null || true

echo
echo "DONE. 应用目录: $APP_DIR (git)"
echo "密钥: $APP_DIR/$ENV_REL"
echo "旧目录备份: $OLD_DIR"
echo "部署: bash $APP_DIR/deploy/scripts/server-deploy.sh"
