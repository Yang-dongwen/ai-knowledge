<#
.SYNOPSIS
  本机一键：把 deploy/app.env 同步到服务器，并重启容器使配置生效。

.DESCRIPTION
  仅同步隐私/配置文件，不传代码。
  代码发布请用 git push（GitHub Actions 自动部署）。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File deploy/scripts/sync-env-local.ps1

.EXAMPLE
  # 只上传 env，不重启（下次部署或手动 restart 再生效）
  powershell -ExecutionPolicy Bypass -File deploy/scripts/sync-env-local.ps1 -NoRestart
#>
param(
  [string]$HostName = "13.201.82.24",
  [string]$User = "ubuntu",
  [string]$PemPath = "$env:USERPROFILE\Downloads\aws_common\dw-yindu.pem",
  [string]$RemoteAppDir = "/home/ubuntu/auto-exchange",
  [string]$LocalEnv = "",
  [switch]$NoRestart
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path

if (-not $LocalEnv) {
  $LocalEnv = Join-Path $RepoRoot "deploy/app.env"
}
if (-not (Test-Path $LocalEnv)) {
  throw "本地找不到 $LocalEnv 。请先: cp deploy/app.env.example deploy/app.env 并填写密钥"
}
if (-not (Test-Path $PemPath)) {
  throw "PEM not found: $PemPath"
}

# 简单校验：不要同步空密码占位
$raw = Get-Content $LocalEnv -Raw
if ($raw -match 'YOUR_RDS_ENDPOINT|CHANGE_ME') {
  throw "app.env 仍含 YOUR_RDS_ENDPOINT / CHANGE_ME，请先填真实配置"
}

$remote = "${User}@${HostName}"
$sshBase = @("-i", $PemPath, "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=20")

Write-Host "==> upload $LocalEnv -> ${remote}:${RemoteAppDir}/deploy/app.env" -ForegroundColor Cyan
& scp @sshBase $LocalEnv "${remote}:${RemoteAppDir}/deploy/app.env"
if ($LASTEXITCODE -ne 0) { throw "scp failed" }

$remoteCmd = @"
set -euo pipefail
cd '$RemoteAppDir'
chmod 600 deploy/app.env
cp -f deploy/app.env deploy/.env
chmod 600 deploy/.env
sed -i 's/\r`$//' deploy/app.env deploy/.env
# 去掉可能的 BOM
sed -i '1s/^\xEF\xBB\xBF//' deploy/app.env deploy/.env
echo 'env synced on server'
"@

if (-not $NoRestart) {
  $remoteCmd += @"

echo '==> restart containers with new env'
docker compose -f deploy/docker-compose.lite.yml --env-file deploy/app.env up -d
echo '==> status'
docker compose -f deploy/docker-compose.lite.yml --env-file deploy/app.env ps
code=`$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8088/api/ 2>/dev/null || echo 000)
echo "api_http=`$code"
"@
}

$remoteCmdUnix = $remoteCmd -replace "`r`n", "`n"
$tmp = Join-Path $env:TEMP "ae-sync-env.sh"
[System.IO.File]::WriteAllText($tmp, $remoteCmdUnix)

Write-Host "==> apply on server" -ForegroundColor Cyan
& scp @sshBase $tmp "${remote}:/tmp/ae-sync-env.sh"
& ssh @sshBase $remote "bash /tmp/ae-sync-env.sh; rm -f /tmp/ae-sync-env.sh"
if ($LASTEXITCODE -ne 0) { throw "remote apply failed" }

Write-Host ""
Write-Host "OK. Secrets synced." -ForegroundColor Green
if (-not $NoRestart) {
  Write-Host "Containers restarted with new env. Open: http://${HostName}:8088/"
} else {
  Write-Host "Env uploaded only. Restart later: ssh ... 'cd $RemoteAppDir && docker compose -f deploy/docker-compose.lite.yml --env-file deploy/app.env up -d'"
}
