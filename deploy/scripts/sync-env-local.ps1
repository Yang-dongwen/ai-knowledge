<#
.SYNOPSIS
  本机一键：同步 deploy/env/app.env（真实密钥）到服务器并重启容器
  Spring 配置（application-ec2.yml）走 git push，不要用本脚本传 yml
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
$LocalEnv = Join-Path $RepoRoot "deploy\env\app.env"
# 兼容旧路径
if (-not (Test-Path $LocalEnv)) {
  $legacy = Join-Path $RepoRoot "deploy\app.env"
  if (Test-Path $legacy) { $LocalEnv = $legacy }
}
if (-not (Test-Path $LocalEnv)) { throw "缺少 deploy/env/app.env（可从 app.env.example 复制）" }
if (-not (Test-Path $PemPath)) { throw "PEM not found: $PemPath" }

$remote = "${User}@${HostName}"
$ssh = @("-i", $PemPath, "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=20")

Write-Host "==> scp app.env -> server deploy/env/" -ForegroundColor Cyan
& ssh @ssh $remote "mkdir -p ${RemoteAppDir}/deploy/env"
& scp @ssh $LocalEnv "${remote}:${RemoteAppDir}/deploy/env/app.env"
if ($LASTEXITCODE -ne 0) { throw "scp failed" }

$cmd = "set -e; cd $RemoteAppDir; chmod 600 deploy/env/app.env; sed -i 's/\r`$//' deploy/env/app.env; echo env_ok"
if (-not $NoRestart) {
  $cmd += "; docker compose -f deploy/stack/compose.lite.yml --env-file deploy/env/app.env up -d; curl -s -o /dev/null -w 'api:%{http_code}\n' http://127.0.0.1:8088/api/ || true"
}
& ssh @ssh $remote $cmd
Write-Host "OK." -ForegroundColor Green
