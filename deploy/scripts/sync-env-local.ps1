<#
.SYNOPSIS
  本机 → EC2 同步 deploy/env/app.env，并重启 compose.lite
#>
param(
  [string]$HostName = "13.201.82.24",
  [string]$User = "ubuntu",
  [string]$PemPath = "$env:USERPROFILE\Downloads\aws_common\dw-yindu.pem",
  [string]$RemoteAppDir = "/home/ubuntu/auto-exchange",
  [switch]$NoRestart
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path

$EnvRel = "deploy/env/app.env"
$ComposeRel = "deploy/stack/compose.lite.yml"
$LocalEnv = Join-Path $RepoRoot ($EnvRel -replace "/", "\")

$LegacyEnv = Join-Path $RepoRoot "deploy\app.env"
if (-not (Test-Path $LocalEnv) -and (Test-Path $LegacyEnv)) {
  $envDir = Join-Path $RepoRoot "deploy\env"
  New-Item -ItemType Directory -Force -Path $envDir | Out-Null
  Copy-Item $LegacyEnv $LocalEnv -Force
  Write-Host "==> migrated local deploy/app.env -> $EnvRel" -ForegroundColor Yellow
}

if (-not (Test-Path $LocalEnv)) {
  throw "缺少 $EnvRel 。请: copy deploy\env\app.env.example deploy\env\app.env 后填写"
}
if (-not (Test-Path $PemPath)) { throw "PEM not found: $PemPath" }

$remote = "${User}@${HostName}"
$ssh = @("-i", $PemPath, "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=20")

Write-Host "==> scp $EnvRel -> ${remote}:${RemoteAppDir}/$EnvRel" -ForegroundColor Cyan
& ssh @ssh $remote "mkdir -p ${RemoteAppDir}/deploy/env"
& scp @ssh $LocalEnv "${remote}:${RemoteAppDir}/${EnvRel}"
if ($LASTEXITCODE -ne 0) { throw "scp failed" }

# 远端：优先用 server-deploy SKIP_GIT=1（含 override 逻辑）
$remoteCmd = @"
set -e
cd '$RemoteAppDir'
chmod 600 $EnvRel
sed -i 's/\r`$//' $EnvRel
echo env_ok path=$EnvRel
"@

if (-not $NoRestart) {
  $remoteCmd += @"

export APP_DIR='$RemoteAppDir' COMPOSE_FILE=$ComposeRel SKIP_GIT=1
bash deploy/scripts/server-deploy.sh
"@
}

$remoteCmdUnix = $remoteCmd -replace "`r`n", "`n"
& ssh @ssh $remote $remoteCmdUnix
if ($LASTEXITCODE -ne 0) { throw "remote failed" }
Write-Host "OK." -ForegroundColor Green
