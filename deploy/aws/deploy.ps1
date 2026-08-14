<#
.SYNOPSIS
  本机打包代码 → scp → 远端 server-deploy（不覆盖服务器密钥）

.NOTES
  路径:
    服务器密钥  deploy/env/app.env（远端已有则保留）
    compose     deploy/stack/compose.lite.yml
#>
param(
  [string]$HostName = "13.201.82.24",
  [string]$User = "ubuntu",
  [string]$PemPath = "$env:USERPROFILE\Downloads\aws_common\dw-yindu.pem",
  [string]$RemoteAppDir = "~/auto-exchange",
  [string]$ComposeFile = "deploy/stack/compose.lite.yml",
  [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
Set-Location $RepoRoot

if (-not (Test-Path $PemPath)) { throw "PEM not found: $PemPath" }

$remote = "${User}@${HostName}"
$ssh = @("-i", $PemPath, "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=20")
$stage = Join-Path $env:TEMP "auto-exchange-deploy-stage"
$tgz = Join-Path $env:TEMP "auto-exchange-deploy.tgz"

Write-Host "==> stage code" -ForegroundColor Cyan
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Path $stage | Out-Null

$dirs = @("deploy", "okx-bot", "okx-trading-web", "aigen-remotion", ".github")
foreach ($d in $dirs) {
  $src = Join-Path $RepoRoot $d
  if (-not (Test-Path $src)) { continue }
  $dst = Join-Path $stage $d
  if ($d -eq "okx-bot") {
    robocopy $src $dst /E /XD target data .venv __pycache__ .idea /XF *.class /NFL /NDL /NJH /NJS /nc /ns /np | Out-Null
  } elseif ($d -eq "okx-trading-web" -or $d -eq "aigen-remotion") {
    robocopy $src $dst /E /XD node_modules dist .vite /NFL /NDL /NJH /NJS /nc /ns /np | Out-Null
  } else {
    # 不打包本机密钥
    robocopy $src $dst /E /XF app.env .env SECRETS_INVENTORY.env .admin-once.txt app.env.local /NFL /NDL /NJH /NJS /nc /ns /np | Out-Null
  }
}

foreach ($f in @("README.md", ".gitignore")) {
  $p = Join-Path $RepoRoot $f
  if (Test-Path $p) { Copy-Item $p $stage -Force }
}

if (Test-Path $tgz) { Remove-Item $tgz -Force }
Write-Host "==> tar" -ForegroundColor Cyan
tar -czf $tgz -C $stage .
Write-Host "    $([math]::Round((Get-Item $tgz).Length / 1MB, 2)) MB"

Write-Host "==> scp package" -ForegroundColor Cyan
& scp @ssh $tgz "${remote}:/tmp/auto-exchange-deploy.tgz"
if ($LASTEXITCODE -ne 0) { throw "scp failed" }

# 远端：解压 + 恢复密钥到 deploy/env/app.env + 可选 server-deploy
$remoteScript = @"
set -euo pipefail
APP_DIR=$RemoteAppDir
ENV_REL=deploy/env/app.env
mkdir -p "`$APP_DIR"
ENV_BAK=`$(mktemp)
# 备份：新路径优先，再旧路径
if [ -f "`$APP_DIR/`$ENV_REL" ]; then cp -a "`$APP_DIR/`$ENV_REL" "`$ENV_BAK"
elif [ -f "`$APP_DIR/deploy/app.env" ]; then cp -a "`$APP_DIR/deploy/app.env" "`$ENV_BAK"
elif [ -f "`$APP_DIR/deploy/.env" ]; then cp -a "`$APP_DIR/deploy/.env" "`$ENV_BAK"
else : > "`$ENV_BAK"
fi
tar -xzf /tmp/auto-exchange-deploy.tgz -C "`$APP_DIR"
mkdir -p "`$APP_DIR/deploy/env"
if [ -s "`$ENV_BAK" ]; then
  cp -a "`$ENV_BAK" "`$APP_DIR/`$ENV_REL"
  chmod 600 "`$APP_DIR/`$ENV_REL"
fi
rm -f "`$ENV_BAK" /tmp/auto-exchange-deploy.tgz
chmod +x "`$APP_DIR/deploy/aws/"*.sh 2>/dev/null || true
mkdir -p "`$HOME/bin"
ln -sfn "`$APP_DIR/deploy/aws/server-deploy.sh" "`$HOME/bin/deploy"
ln -sfn "`$APP_DIR/deploy/aws/up.sh" "`$HOME/bin/up"
"@

if (-not $SkipBuild) {
  $remoteScript += @"

export APP_DIR COMPOSE_FILE=$ComposeFile SKIP_GIT=1
bash "`$APP_DIR/deploy/aws/server-deploy.sh"
"@
} else {
  $remoteScript += "echo 'SkipBuild: code only'`n"
}

Write-Host "==> remote extract + deploy" -ForegroundColor Cyan
$tmpSh = Join-Path $env:TEMP "ae-remote-deploy.sh"
[System.IO.File]::WriteAllText($tmpSh, ($remoteScript -replace "`r`n", "`n"))
& scp @ssh $tmpSh "${remote}:/tmp/ae-remote-deploy.sh"
& ssh @ssh $remote "bash /tmp/ae-remote-deploy.sh; rm -f /tmp/ae-remote-deploy.sh"
if ($LASTEXITCODE -ne 0) { throw "remote deploy failed" }

Write-Host ""
Write-Host "OK. http://${HostName}:8088/" -ForegroundColor Green
