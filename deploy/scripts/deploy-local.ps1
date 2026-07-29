<#
.SYNOPSIS
  本机一键部署到 EC2：打包代码 → scp → 远端 docker compose 重建

.DESCRIPTION
  不依赖服务器 git；密钥不同步（保留服务器 deploy/env/app.env）。
  适合 CI 挂了或紧急发布。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File deploy/scripts/deploy-local.ps1
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

if (-not (Test-Path $PemPath)) {
  throw "PEM not found: $PemPath"
}

$remote = "${User}@${HostName}"
$ssh = @("-i", $PemPath, "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=20")
$stage = Join-Path $env:TEMP "auto-exchange-deploy-stage"
$tgz = Join-Path $env:TEMP "auto-exchange-deploy.tgz"

Write-Host "==> stage code (exclude heavy dirs)" -ForegroundColor Cyan
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
    # deploy: 不要把本机密钥覆盖服务器
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
$sizeMb = [math]::Round((Get-Item $tgz).Length / 1MB, 2)
Write-Host "    package ${sizeMb} MB"

Write-Host "==> scp -> ${remote}:/tmp/auto-exchange-deploy.tgz" -ForegroundColor Cyan
& scp @ssh $tgz "${remote}:/tmp/auto-exchange-deploy.tgz"
if ($LASTEXITCODE -ne 0) { throw "scp failed" }

$remoteScript = @"
set -euo pipefail
APP_DIR=$RemoteAppDir
mkdir -p "`$APP_DIR"
# 备份密钥（新/旧路径）
ENV_BAK=`$(mktemp)
if [ -f "`$APP_DIR/deploy/env/app.env" ]; then cp -a "`$APP_DIR/deploy/env/app.env" "`$ENV_BAK"
elif [ -f "`$APP_DIR/deploy/app.env" ]; then cp -a "`$APP_DIR/deploy/app.env" "`$ENV_BAK"
elif [ -f "`$APP_DIR/deploy/.env" ]; then cp -a "`$APP_DIR/deploy/.env" "`$ENV_BAK"
fi
tar -xzf /tmp/auto-exchange-deploy.tgz -C "`$APP_DIR"
mkdir -p "`$APP_DIR/deploy/env"
if [ -s "`$ENV_BAK" ]; then
  cp -a "`$ENV_BAK" "`$APP_DIR/deploy/env/app.env"
  chmod 600 "`$APP_DIR/deploy/env/app.env"
fi
rm -f "`$ENV_BAK" /tmp/auto-exchange-deploy.tgz
chmod +x "`$APP_DIR/deploy/scripts/"*.sh 2>/dev/null || true
mkdir -p "`$HOME/bin"
ln -sfn "`$APP_DIR/deploy/scripts/server-deploy.sh" "`$HOME/bin/deploy"
ln -sfn "`$APP_DIR/deploy/scripts/up.sh" "`$HOME/bin/up"
"@

if (-not $SkipBuild) {
  $remoteScript += @"

export APP_DIR COMPOSE_FILE=$ComposeFile SKIP_GIT=1
bash "`$APP_DIR/deploy/scripts/server-deploy.sh"
"@
} else {
  $remoteScript += "echo 'SkipBuild: code synced only'`n"
}

Write-Host "==> remote extract + deploy" -ForegroundColor Cyan
$remoteScriptUnix = $remoteScript -replace "`r`n", "`n"
$tmpSh = Join-Path $env:TEMP "ae-remote-deploy.sh"
[System.IO.File]::WriteAllText($tmpSh, $remoteScriptUnix)
& scp @ssh $tmpSh "${remote}:/tmp/ae-remote-deploy.sh"
& ssh @ssh $remote "bash /tmp/ae-remote-deploy.sh; rm -f /tmp/ae-remote-deploy.sh"
if ($LASTEXITCODE -ne 0) { throw "remote deploy failed" }

Write-Host ""
Write-Host "OK. Open: http://${HostName}:8088/" -ForegroundColor Green
