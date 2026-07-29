<#
.SYNOPSIS
  本地启动 profile=local（application-local.yml）
#>
$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$localYml = Join-Path $RepoRoot "okx-bot\src\main\resources\application-local.yml"

if (-not (Test-Path $localYml)) {
  Write-Host @"
缺少 application-local.yml（本地真实配置，不进 Git）。

  1) 复制模板:
     copy okx-bot\src\main\resources\application-local.yml.example okx-bot\src\main\resources\application-local.yml
  2) 或从 deploy/env/app.env 生成:
     python deploy/scripts/gen_profile_yml.py
"@ -ForegroundColor Yellow
  throw "application-local.yml not found"
}

$env:SPRING_PROFILES_ACTIVE = "local"
@('SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD') | ForEach-Object {
  Remove-Item "Env:$_" -ErrorAction SilentlyContinue
}

Write-Host "==> profile=local  config=$localYml" -ForegroundColor Cyan
Set-Location (Join-Path $RepoRoot "okx-bot")
mvn spring-boot:run
