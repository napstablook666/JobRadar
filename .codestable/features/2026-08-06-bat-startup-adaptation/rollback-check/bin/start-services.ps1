# GetJobs start services
param(
    [switch]$NoBrowser
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Front = Join-Path $Root "front"
$LogDir = Join-Path $Root "target\local-run"
$FrontLog = Join-Path $LogDir "front-dev.log"
$BackLog = Join-Path $LogDir "backend-bootRun.log"
$FrontRun = Join-Path $LogDir "run-front.bat"
$BackRun = Join-Path $LogDir "run-backend.bat"

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $Root "target\logs") | Out-Null

function Write-Step([string]$msg) { Write-Host $msg }
function Test-HttpOk([string]$url) {
    try {
        Invoke-WebRequest -UseBasicParsing -TimeoutSec 1 -Uri $url | Out-Null
        return $true
    } catch {
        return $false
    }
}

function Test-TcpPort([string]$targetHost, [int]$port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($targetHost, $port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne(1000)) { return $false }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Get-JavaVersionText([string]$javaExe) {
    # java -version writes to stderr; under $ErrorActionPreference=Stop that becomes terminating.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $lines = & $javaExe -version 2>&1 | ForEach-Object { "$_" }
        return ($lines -join "`n")
    } catch {
        return ""
    } finally {
        $ErrorActionPreference = $prev
    }
}

function Test-IsJdk21([string]$jdkHome) {
    if (-not $jdkHome) { return $false }
    $javaExe = Join-Path $jdkHome "bin\java.exe"
    if (-not (Test-Path -LiteralPath $javaExe)) { return $false }
    $ver = Get-JavaVersionText $javaExe
    return ($ver -match 'version "21[\."]')
}

function Write-Utf8NoBom([string]$path, [string]$content) {
    $dir = Split-Path -Parent $path
    if ($dir -and -not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, $content, $enc)
}

Write-Host "==============================================="
Write-Host " GetJobs start"
Write-Host " Root: $Root"
Write-Host "==============================================="
Write-Host ""

# Prefer real JDK 21 (project toolchain = 21). Ignore JAVA_HOME if it is not 21.
$searchHomes = @()
if ($env:JAVA_HOME) { $searchHomes += $env:JAVA_HOME }
$searchHomes += "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
$searchHomes += @(Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like "jdk-21*" } | Select-Object -ExpandProperty FullName)
$searchHomes += @(Get-ChildItem "C:\Program Files\Java" -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like "jdk-21*" } | Select-Object -ExpandProperty FullName)
$searchHomes += @(Get-ChildItem "C:\Program Files\Microsoft" -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like "jdk-21*" } | Select-Object -ExpandProperty FullName)

$javaHome = $null
foreach ($h in ($searchHomes | Where-Object { $_ } | Select-Object -Unique)) {
    if (Test-IsJdk21 $h) {
        $javaHome = $h
        break
    }
}

if ($javaHome) {
    $env:JAVA_HOME = $javaHome
    $env:Path = "$javaHome\bin;" + $env:Path
    Write-Step "[OK] JAVA_HOME=$javaHome (JDK 21)"
} else {
    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) {
        Write-Host "[ERROR] JDK 21 not found. Install Temurin 21 or set JAVA_HOME to JDK 21."
        if (-not $env:GETJOBS_NO_PAUSE) { pause }
        exit 1
    }
    $probe = Get-JavaVersionText "java"
    if ($probe -notmatch 'version "21[\."]') {
        Write-Host "[ERROR] java on PATH is not JDK 21:"
        Write-Host $probe
        if (-not $env:GETJOBS_NO_PAUSE) { pause }
        exit 1
    }
    Write-Step "[WARN] JAVA_HOME not set; using PATH java 21"
}

$verLine = (Get-JavaVersionText "java") -split "`n" | Select-Object -First 1
if ($verLine) { Write-Host $verLine }

# package manager
$pmCmd = $null
if (Get-Command pnpm -ErrorAction SilentlyContinue) {
    $pmCmd = "call pnpm.cmd"
    Write-Step "[OK] using pnpm"
} elseif (Get-Command npm -ErrorAction SilentlyContinue) {
    $pmCmd = "call npx.cmd --yes pnpm"
    Write-Step "[WARN] pnpm missing, using: npx --yes pnpm"
} else {
    Write-Host "[ERROR] pnpm/npm not found"
    if (-not $env:GETJOBS_NO_PAUSE) { pause }
    exit 1
}

if (-not (Test-Path -LiteralPath (Join-Path $Front "package.json"))) {
    Write-Host "[ERROR] front package.json missing: $Front"
    if (-not $env:GETJOBS_NO_PAUSE) { pause }
    exit 1
}
if (-not (Test-Path -LiteralPath (Join-Path $Root "gradlew.bat"))) {
    Write-Host "[ERROR] gradlew.bat missing"
    if (-not $env:GETJOBS_NO_PAUSE) { pause }
    exit 1
}

Write-Host ""
Write-Step "[1/4] Stop old processes..."
& (Join-Path $PSScriptRoot "stop-services.ps1") -Quiet
Start-Sleep -Seconds 2

# helper bats for titled consoles (ASCII-friendly, no BOM)
$frontBat = @"
@echo off
chcp 65001 >nul
title GetJobs-Front
cd /d "$Front"
echo [GetJobs-Front] starting...
echo log: $FrontLog
$pmCmd dev > "$FrontLog" 2>&1
echo [GetJobs-Front] exited code=%ERRORLEVEL%
pause
"@

$backLines = New-Object System.Collections.Generic.List[string]
$backLines.Add("@echo off") | Out-Null
$backLines.Add("chcp 65001 >nul") | Out-Null
$backLines.Add("title GetJobs-Backend") | Out-Null
$backLines.Add("cd /d `"$Root`"") | Out-Null
if ($javaHome) {
    $backLines.Add("set `"JAVA_HOME=$javaHome`"") | Out-Null
    $backLines.Add("set `"PATH=%JAVA_HOME%\bin;%PATH%`"") | Out-Null
}
$backLines.Add("echo [GetJobs-Backend] starting bootRun...") | Out-Null
$backLines.Add("echo log: $BackLog") | Out-Null
$backLines.Add("call gradlew.bat bootRun > `"$BackLog`" 2>&1") | Out-Null
$backLines.Add("echo [GetJobs-Backend] exited code=%ERRORLEVEL%") | Out-Null
$backLines.Add("pause") | Out-Null

Write-Utf8NoBom -path $FrontRun -content ($frontBat -replace "`r?`n", "`r`n")
Write-Utf8NoBom -path $BackRun -content (($backLines -join "`r`n") + "`r`n")

Write-Host ""
Write-Step "[2/4] Start frontend http://localhost:6866"
$lock = Join-Path $Front ".next\dev\lock"
if (Test-Path -LiteralPath $lock) {
    Remove-Item -LiteralPath $lock -Force -ErrorAction SilentlyContinue
}
Start-Process -FilePath "cmd.exe" -ArgumentList @("/d", "/c", "call", "`"$FrontRun`"") -WorkingDirectory $Front -WindowStyle Normal

$ready = $false
for ($i = 1; $i -le 60; $i++) {
    if (Test-HttpOk "http://127.0.0.1:6866/") {
        Write-Step "[OK] frontend ready (~${i}s) log: $FrontLog"
        $ready = $true
        break
    }
    Start-Sleep -Seconds 1
}
if (-not $ready) {
    Write-Step "[WARN] frontend not ready in 60s, continue. log: $FrontLog"
}

Write-Host ""
Write-Step "[3/4] Start backend http://localhost:8888"
Start-Process -FilePath "cmd.exe" -ArgumentList @("/d", "/c", "call", "`"$BackRun`"") -WorkingDirectory $Root -WindowStyle Normal

$ready = $false
for ($i = 1; $i -le 120; $i++) {
    if (Test-TcpPort "127.0.0.1" 8888) {
        Write-Step "[OK] backend port ready (~${i}s) log: $BackLog"
        $ready = $true
        break
    }
    Start-Sleep -Seconds 1
}
if (-not $ready) {
    Write-Step "[WARN] backend not ready in 120s. check log: $BackLog"
}

Write-Host ""
Write-Step "[4/4] Open admin page"
if (-not $NoBrowser) {
    Start-Process "http://localhost:6866/"
}

Write-Host ""
Write-Host "==============================================="
Write-Host " Start done"
Write-Host " Admin:   http://localhost:6866/"
Write-Host " API:     http://localhost:8888/"
Write-Host " Front:   $FrontLog"
Write-Host " Backend: $BackLog"
Write-Host " Stop:    double-click 一键停止.bat  (or stop.bat)"
Write-Host "==============================================="
Write-Host ""
Write-Host "Tip: Boss login opens system Chrome (CDP 7866) hands-off. Scan QR there."
Write-Host ""
if (-not $env:GETJOBS_NO_PAUSE) { pause }
exit 0
