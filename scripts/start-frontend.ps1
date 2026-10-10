#Requires -Version 5.1
<#
.SYNOPSIS
    本地启动前端（Vite 开发服务器）。

.DESCRIPTION
    在 frontend/ 目录执行 npm run dev。若尚未安装依赖会先自动执行 npm ci。

    开发服务器默认监听 http://127.0.0.1:5173，并把 /api、/actuator 代理到
    http://127.0.0.1:8080（见 frontend/vite.config.ts），因此需要后端同时运行。

.EXAMPLE
    ./scripts/start-frontend.ps1
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$frontendDir = Join-Path $repoRoot 'frontend'
$packageJson = Join-Path $frontendDir 'package.json'
$nodeModules = Join-Path $frontendDir 'node_modules'

if (-not (Test-Path -LiteralPath $packageJson)) {
    throw "找不到 $packageJson，请在仓库内运行本脚本。"
}

if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    throw '未找到 npm，请先安装 Node.js 22+（可执行 ./scripts/check-environment.ps1 自检）。'
}

Push-Location $frontendDir
try {
    $ErrorActionPreference = 'Continue'

    if (-not (Test-Path -LiteralPath $nodeModules)) {
        Write-Host '未检测到 node_modules，先执行 npm ci 安装依赖…' -ForegroundColor Yellow
        & npm ci
        if ($LASTEXITCODE -ne 0) {
            Write-Host "依赖安装失败，退出码 $LASTEXITCODE。" -ForegroundColor Red
            exit $LASTEXITCODE
        }
    }

    Write-Host "启动前端：$frontendDir" -ForegroundColor Cyan
    Write-Host '开发地址 http://127.0.0.1:5173 ；按 Ctrl+C 停止。' -ForegroundColor DarkGray
    Write-Host ''

    & npm run dev
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($exitCode -ne 0) {
    Write-Host "前端进程退出，退出码 $exitCode。" -ForegroundColor Red
    exit $exitCode
}