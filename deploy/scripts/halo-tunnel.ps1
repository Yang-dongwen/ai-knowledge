<#
.SYNOPSIS
  把 EC2 上 Halo 容器的 8090 转到本机 127.0.0.1:18090（默认），供本地 okx-bot 发文联调。
  说明见 deploy/docs/local-run.md
#>
param(
  [string]$HostName = "13.201.82.24",
  [string]$User = "ubuntu",
  [string]$PemPath = "$env:USERPROFILE\Downloads\aws_common\dw-yindu.pem",
  # 默认 18090：本机 Clash 等常劫持 8090，会导致 502
  [int]$LocalPort = 18090
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $PemPath)) { throw "PEM not found: $PemPath" }

$sshBase = @("-i", $PemPath, "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=20")
$remote = "${User}@${HostName}"

Write-Host "==> resolve halo container IP" -ForegroundColor Cyan
$ip = (& ssh @sshBase $remote "docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' auto-exchange-lite-halo-1").Trim()
if (-not $ip) { throw "cannot resolve halo IP; is the container running?" }
Write-Host "    halo=$ip:8090 -> 127.0.0.1:$LocalPort"
Write-Host "    本机 okx-bot 请设 HALO_BASE_URL=http://127.0.0.1:$LocalPort" -ForegroundColor Yellow

Write-Host "==> ssh -N -L (Ctrl+C to stop)" -ForegroundColor Cyan
& ssh @sshBase -N -L "${LocalPort}:${ip}:8090" $remote
