#Requires -Version 5.1
<#
.SYNOPSIS
    检查本地开发所需的工具链是否就绪。

.DESCRIPTION
    校验 JDK / Maven / Node.js / npm 的可用性与最低版本要求，并顺带探测 Docker。
    Docker 为可选依赖（仅用于起本地 PostgreSQL / Redis 或容器化部署），缺失只提示不报错。

    全部必需项通过时退出码为 0，否则为 1，可直接用于 CI 或前置判断。

.EXAMPLE
    ./scripts/check-environment.ps1
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# 原生命令把版本号写到 stderr 属于正常行为，不要升级成终止性错误
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$script:results = @()

function Add-Result {
    param(
        [Parameter(Mandatory)][string] $Name,
        [Parameter(Mandatory)][bool]   $Ok,
        [string] $Detail = '',
        [string] $Hint = '',
        [string] $Advisory = '',
        [bool]   $Required = $true
    )

    $script:results += [pscustomobject]@{
        Name     = $Name
        Ok       = $Ok
        Required = $Required
        Detail   = $Detail
        Hint     = $Hint
        Advisory = $Advisory
    }
}

function Get-VersionToken {
    param(
        [AllowEmptyCollection()][AllowNull()][string[]] $Lines = @(),
        [Parameter(Mandatory)][string]                  $Pattern
    )

    if ($null -eq $Lines) { return $null }
    foreach ($line in $Lines) {
        if ($line -match $Pattern) { return $Matches[1] }
    }
    return $null
}

function Test-VersionAtLeast {
    param([string] $Actual, [string] $Minimum)

    if ([string]::IsNullOrWhiteSpace($Actual)) { return $false }
    try { return ([version] $Actual -ge [version] $Minimum) } catch { return $false }
}

function Invoke-VersionCommand {
    param(
        [Parameter(Mandatory)][string] $Command,
        [string[]] $Arguments = @('--version')
    )

    # Windows PowerShell 5.1 会把原生命令写到 stderr 的内容当成错误记录，
    # 在 $ErrorActionPreference = 'Stop' 下直接抛异常（java -version / mvn -v 正是写 stderr），
    # 因此这里临时放宽错误策略，只做输出采集。
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        # 命令不存在会抛 CommandNotFoundException，统一收敛成空数组
        return @(& $Command @Arguments 2>&1 | ForEach-Object { "$_" })
    } catch {
        return @()
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Get-ToolVersion {
    param(
        [Parameter(Mandatory)][string] $Command,
        [string[]] $Arguments,
        [Parameter(Mandatory)][string] $Pattern
    )

    $output = @(Invoke-VersionCommand -Command $Command -Arguments $Arguments)
    return Get-VersionToken -Lines $output -Pattern $Pattern
}

# ------------------------------------------------------------
# JDK
# ------------------------------------------------------------
$javaMin = '21.0'
$javaVersion = Get-ToolVersion -Command 'java' -Arguments @('-version') -Pattern 'version "([^"]+)"'
if (-not $javaVersion) {
    Add-Result -Name 'JDK' -Ok $false -Detail '未找到 java' -Hint "请安装 JDK $javaMin+ 并加入 PATH"
} else {
    Add-Result -Name 'JDK' -Ok (Test-VersionAtLeast $javaVersion $javaMin) -Detail "java $javaVersion" `
        -Hint "版本过低，需要 JDK $javaMin+（项目 target 为 Java 21）"
}

# ------------------------------------------------------------
# Maven
# ------------------------------------------------------------
$mavenMin = '3.6'
$mavenRecommended = '3.9'
$mavenVersion = Get-ToolVersion -Command 'mvn' -Arguments @('-v') -Pattern 'Apache Maven ([0-9][^\s]*)'
if (-not $mavenVersion) {
    Add-Result -Name 'Maven' -Ok $false -Detail '未找到 mvn' -Hint "请安装 Maven $mavenMin+ 并加入 PATH"
} else {
    Add-Result -Name 'Maven' -Ok (Test-VersionAtLeast $mavenVersion $mavenMin) -Detail "maven $mavenVersion" `
        -Advisory $(if (Test-VersionAtLeast $mavenVersion $mavenRecommended) { '' } `
                else { "可以正常构建；建议升级到 Maven $mavenRecommended+" })
}

# ------------------------------------------------------------
# Node.js / npm
# ------------------------------------------------------------
$nodeMin = '22.0'
$nodeVersion = Get-ToolVersion -Command 'node' -Arguments @('-v') -Pattern 'v([0-9][^\s]*)'
if (-not $nodeVersion) {
    Add-Result -Name 'Node.js' -Ok $false -Detail '未找到 node' -Hint "请安装 Node.js $nodeMin+"
} else {
    Add-Result -Name 'Node.js' -Ok (Test-VersionAtLeast $nodeVersion $nodeMin) -Detail "node v$nodeVersion" `
        -Hint "版本过低，需要 Node.js $nodeMin+"
}

$npmVersion = Get-ToolVersion -Command 'npm' -Arguments @('-v') -Pattern '([0-9][^\s]*)'
if (-not $npmVersion) {
    Add-Result -Name 'npm' -Ok $false -Detail '未找到 npm' -Hint '请安装 npm（随 Node.js 一起分发）'
} else {
    Add-Result -Name 'npm' -Ok $true -Detail "npm $npmVersion"
}

# ------------------------------------------------------------
# Docker（可选）
# ------------------------------------------------------------
$dockerVersion = Get-ToolVersion -Command 'docker' -Arguments @('--version') -Pattern 'Docker version ([0-9][^\s,]*)'
if (-not $dockerVersion) {
    Add-Result -Name 'Docker' -Ok $false -Required $false -Detail '未找到 docker' `
        -Hint '可选：本地起 PostgreSQL / Redis 或容器化部署时需要'
} else {
    $composeVersion = Get-ToolVersion -Command 'docker' -Arguments @('compose', 'version') -Pattern 'v?([0-9][^\s]*)'
    Add-Result -Name 'Docker' -Ok $true -Required $false -Detail "docker $dockerVersion" `
        -Hint $(if ($composeVersion) { '' } else { '缺少 docker compose 插件（v2）' })
}

# ------------------------------------------------------------
# 汇总输出
# ------------------------------------------------------------
Write-Host ''
Write-Host '本地开发环境检查' -ForegroundColor Cyan
Write-Host ('-' * 62)

foreach ($r in $script:results) {
    $label = if ($r.Ok) { '[ OK ]' } elseif ($r.Required) { '[FAIL]' } else { '[WARN]' }
    $color = if ($r.Ok) { 'Green' } elseif ($r.Required) { 'Red' } else { 'Yellow' }

    Write-Host ("{0} {1,-10} {2}" -f $label, $r.Name, $r.Detail) -ForegroundColor $color
    if (-not $r.Ok -and $r.Hint) {
        Write-Host ("       {0}" -f $r.Hint) -ForegroundColor DarkGray
    }
    if ($r.Advisory) {
        Write-Host ("       {0}" -f $r.Advisory) -ForegroundColor DarkGray
    }
}

Write-Host ('-' * 62)

$failed = @($script:results | Where-Object { $_.Required -and -not $_.Ok })
if ($failed.Count -eq 0) {
    Write-Host '必需项全部通过，可以开始开发。' -ForegroundColor Green
    exit 0
}

Write-Host ("存在 {0} 项必需依赖未满足，请先处理上面的 [FAIL] 项。" -f $failed.Count) -ForegroundColor Red
exit 1