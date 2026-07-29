<#
.SYNOPSIS
  部署 Cloudflare Worker 反代（固定 workers.dev 域名）
.NOTES
  需先: npx wrangler login
  或设置 $env:CLOUDFLARE_API_TOKEN
#>
$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
Set-Location (Join-Path $RepoRoot "deploy\worker-proxy")

Write-Host "==> wrangler deploy" -ForegroundColor Cyan
npx --yes wrangler@4 deploy
if ($LASTEXITCODE -ne 0) { throw "wrangler deploy failed" }

Write-Host ""
Write-Host "OK: https://shrill-dew-d53a.dwcode.workers.dev" -ForegroundColor Green
Write-Host "建议 PAY_PUBLIC_BASE_URL=https://shrill-dew-d53a.dwcode.workers.dev"
Write-Host "然后: powershell -File deploy/scripts/sync-env-local.ps1"
