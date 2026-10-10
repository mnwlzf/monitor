#Requires -Version 5.1
<#
.SYNOPSIS
    本地启动后端（Spring Boot）。

.DESCRIPTION
    在 backend/ 目录执行 mvn spring-boot:run，配置从仓库根目录的 .env 读取
    （application.yml 中已声明 optional:file:../.env）。

    启动前需要本地已有 PostgreSQL / Redis，可先执行：
        docker compose up -d postgres redis

    注意：仓库根目录的 .env 是为容器化部署准备的，DB_URL / REDIS_HOST 默认是
    compose 服务名（postgres / redis），宿主机无法解析。本地直跑时请把它们改成
    localhost，或用环境变量临时覆盖，例如：
        $env:DB_URL = 'jdbc:postgresql://localhost:5432/monitor'

.EXAMPLE
    ./scripts/start-backend.ps1
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $repoRoot 'backend'
$pomPath = Join-Path $backendDir 'pom.xml'

if (-not (Test-Path -LiteralPath $pomPath)) {
    throw "找不到 $pomPath，请在仓库内运行本脚本。"
}

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw '未找到 mvn，请先安装 Maven 并加入 PATH（可执行 ./scripts/check-environment.ps1 自检）。'
}

# ------------------------------------------------------------
# 提示：.env 中的 compose 服务名在宿主机无法解析
# ------------------------------------------------------------
function Get-DotEnvValue {
    param([string] $Path, [string] $Key)

    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    $pattern = '^\s*' + [regex]::Escape($Key) + '\s*=\s*(.*)$'
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        if ($line -match $pattern) { return $Matches[1].Trim() }
    }
    return $null
}

$envFile = Join-Path $repoRoot '.env'
if (Test-Path -LiteralPath $envFile) {
    $dbUrl = Get-DotEnvValue -Path $envFile -Key 'DB_URL'
    $redisHost = Get-DotEnvValue -Path $envFile -Key 'REDIS_HOST'
    $suspects = @()

    if ($dbUrl -match '//(postgres|db)[:/]') { $suspects += "DB_URL = $dbUrl" }
    if ($redisHost -eq 'redis') { $suspects += "REDIS_HOST = $redisHost" }

    if ($suspects.Count -gt 0) {
        Write-Host '提示：.env 中以下配置使用了 compose 服务名，宿主机本地直跑时无法解析：' -ForegroundColor Yellow
        $suspects | ForEach-Object { Write-Host "        $_" -ForegroundColor DarkGray }
        Write-Host '      可改用 localhost，或用环境变量临时覆盖后重试。' -ForegroundColor DarkGray
        Write-Host ''
    }
}

# ------------------------------------------------------------
# 启动
# ------------------------------------------------------------
Write-Host "启动后端：$backendDir" -ForegroundColor Cyan
Write-Host '首次启动会下载依赖，请耐心等待；按 Ctrl+C 停止。' -ForegroundColor DarkGray
Write-Host ''

Push-Location $backendDir
try {
    # 长驻进程的日志走 stdout/stderr，放宽错误策略，改用退出码判断
    $ErrorActionPreference = 'Continue'
    & mvn spring-boot:run
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($exitCode -ne 0) {
    Write-Host "后端进程退出，退出码 $exitCode。" -ForegroundColor Red
    exit $exitCode
}