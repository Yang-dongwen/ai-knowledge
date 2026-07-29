<#
.SYNOPSIS
  本机 → EC2 同步 deploy/env/app.env，并重启 compose.lite

.NOTES
  路径（相对仓库根）:
    本机/服务器密钥  deploy/env/app.env
    生产 compose     deploy/stack/compose.lite.yml
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

# 标准路径
$EnvRel = "deploy/env/app.env"
$ComposeRel = "deploy/stack/compose.lite.yml"
$LocalEnv = Join-Path $RepoRoot ($EnvRel -replace "/", "\")

# 旧路径兼容：本机若还在 deploy/app.env，先迁到 env/
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

$remoteCmd = @"
set -e
cd '$RemoteAppDir'
chmod 600 $EnvRel
sed -i 's/\r`$//' $EnvRel
echo env_ok path=$EnvRel
"@

if (-not $NoRestart) {
  $remoteCmd += @"

if [ ! -f $ComposeRel ]; then
  echo "ERROR: 服务器缺少 $ComposeRel ，请先 git pull / 部署新目录结构" >&2
  exit 1
fi
docker compose --project-directory '$RemoteAppDir' -f $ComposeRel --env-file $EnvRel up -d
curl -s -o /dev/null -w 'api:%{http_code}\n' http://127.0.0.1:8088/api/ || true
"@
}

$remoteCmdUnix = $remoteCmd -replace "`r`n", "`n"
& ssh @ssh $remote $remoteCmdUnix
if ($LASTEXITCODE -ne 0) { throw "remote failed" }
Write-Host "OK." -ForegroundColor Green
