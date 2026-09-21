# JobRadar stop services
param(
    [switch]$Quiet
)

$ErrorActionPreference = "SilentlyContinue"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Front = Join-Path $Root "front"
$Ports = @(6866, 6868, 8888, 7866)
$BackendUrl = "http://127.0.0.1:8888"
$GracefulStopTimeoutSeconds = 30

function Write-Info([string]$msg) {
    if (-not $Quiet) { Write-Host $msg }
}

function Get-DeliveryStatus([string]$statusPath) {
    try {
        return Invoke-RestMethod -Uri "$BackendUrl$statusPath" -Method Get -TimeoutSec 3
    } catch {
        return $null
    }
}

function Wait-ForDeliveryShutdown([string]$platform, [string]$statusPath, [string]$stopPath) {
    $status = Get-DeliveryStatus $statusPath
    if ($null -eq $status) {
        Write-Info "  [$platform] Backend status unavailable; continue with process cleanup"
        return
    }
    if (-not [bool]$status.isRunning) {
        Write-Info "  [$platform] No running delivery task"
        return
    }

    Write-Info "  [$platform] Request graceful delivery stop"
    try {
        Invoke-RestMethod -Uri "$BackendUrl$stopPath" -Method Post -TimeoutSec 3 | Out-Null
    } catch {
        Write-Info "  [$platform] stop request returned an error; continue polling status"
    }

    $deadline = (Get-Date).AddSeconds($GracefulStopTimeoutSeconds)
    do {
        Start-Sleep -Milliseconds 250
        $status = Get-DeliveryStatus $statusPath
        if ($status -and -not [bool]$status.isRunning) {
            Write-Info "  [$platform] delivery stopped; progress is flushed"
            return
        }
    } while ((Get-Date) -lt $deadline)

    Write-Info "  [$platform] graceful stop timed out after $GracefulStopTimeoutSeconds seconds; keep force-cleanup fallback"
}

function Test-CmdTouchesRoot([string]$cl, [string]$root) {
    if (-not $cl -or -not $root) { return $false }
    if ($cl.Contains($root)) { return $true }
    # tolerate slash style differences
    $normCl = $cl.Replace('/', '\')
    $normRoot = $root.Replace('/', '\')
    if ($normCl.IndexOf($normRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0) { return $true }
    return $false
}

Write-Info "==============================================="
Write-Info " JobRadar stop"
Write-Info " Root: $Root"
Write-Info "==============================================="
Write-Info ""

Write-Info "[0/6] Request graceful stop for four platform tasks"
@(
    @{ Name = 'boss'; Status = '/api/boss/status'; Stop = '/api/boss/stop' },
    @{ Name = 'liepin'; Status = '/api/liepin/status'; Stop = '/api/liepin/stop' },
    @{ Name = '51job'; Status = '/api/51job/status'; Stop = '/api/51job/stop' },
    @{ Name = 'zhilian'; Status = '/api/zhilian/status'; Stop = '/api/zhilian/stop' }
) | ForEach-Object {
    Wait-ForDeliveryShutdown $_.Name $_.Status $_.Stop
}

Write-Info ""
Write-Info "[1/6] Kill listeners on ports: $($Ports -join ', ')"
foreach ($port in $Ports) {
    $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if (-not $conns) {
        Write-Info "  [:$port] free"
        continue
    }
    $pids = $conns | Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($procId in $pids) {
        if ($procId -and $procId -ne 0) {
            Write-Info "  [:$port] kill PID=$procId"
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
        }
    }
}

Write-Info ""
Write-Info "[2/6] Kill JobRadar-Front / JobRadar-Backend console windows"
# Never kill this stop script or its parent chain (start.bat -> start-services.ps1 may call us).
$selfPid = $PID
$parentPid = $null
try {
    $parentPid = (Get-CimInstance Win32_Process -Filter "ProcessId=$selfPid" -ErrorAction SilentlyContinue).ParentProcessId
} catch {}
$protect = @($selfPid)
if ($parentPid) { $protect += $parentPid }

Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object {
        $_.Name -match 'cmd\.exe|powershell\.exe|pwsh\.exe' -and
        $_.CommandLine -and
        ($_.CommandLine -match 'JobRadar-Front|JobRadar-Backend|run-front\.bat|run-backend\.bat') -and
        ($protect -notcontains $_.ProcessId)
    } |
    ForEach-Object {
        Write-Info "  kill window PID=$($_.ProcessId) Name=$($_.Name)"
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }

Write-Info ""
Write-Info "[3/6] Kill Java bootRun / Gradle for this repo + Next frontend"
Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine } |
    ForEach-Object {
        $cl = $_.CommandLine
        $name = $_.Name
        $kill = $false
        if ($name -match 'java|javaw') {
            if ($cl -match 'JobRadarApplication|bootRun') { $kill = $true }
            elseif ($cl -match 'gradle' -and (Test-CmdTouchesRoot $cl $Root)) { $kill = $true }
            elseif ((Test-CmdTouchesRoot $cl $Root) -and $cl -match 'jobradar|JobRadar') { $kill = $true }
        }
        if ($name -match 'node' -and $cl -match 'next' -and (Test-CmdTouchesRoot $cl $Front)) { $kill = $true }
        if ($kill) {
            Write-Info "  kill PID=$($_.ProcessId) Name=$name"
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
    }

Write-Info ""
Write-Info "[4/6] Kill project Chrome and Playwright browser profiles"
$projectBrowserProfiles = @(
    (Join-Path $Root "browser-data\chrome-cdp-profile").Replace('/', '\'),
    (Join-Path $Root "browser-data\platform-runtimes").Replace('/', '\'),
    (Join-Path $Root "browser-data\platform-login").Replace('/', '\')
)
function Test-ProjectBrowserCommand([string]$commandLine) {
    if (-not $commandLine) { return $false }
    if ($commandLine -like '*remote-debugging-port=7866*') { return $true }
    foreach ($profile in $projectBrowserProfiles) {
        if ($profile -and $commandLine.IndexOf($profile, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            return $true
        }
    }
    return $false
}
Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object {
        $_.Name -match 'chrome|chromium' -and
        (Test-ProjectBrowserCommand $_.CommandLine)
    } |
    ForEach-Object {
        Write-Info "  kill Chrome PID=$($_.ProcessId)"
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }

$lock = Join-Path $Front ".next\dev\lock"
if (Test-Path -LiteralPath $lock) {
    Remove-Item -LiteralPath $lock -Force -ErrorAction SilentlyContinue
    Write-Info "Removed Next lock: $lock"
} else {
    Write-Info "No Next lock found"
}

Write-Info "[5/6] Verify project browser profiles are released"
Start-Sleep -Milliseconds 500
$remainingProjectBrowsers = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object {
        $_.Name -match 'chrome|chromium' -and (Test-ProjectBrowserCommand $_.CommandLine)
    }
if ($remainingProjectBrowsers) {
    $remainingProjectBrowsers | ForEach-Object {
        Write-Info "  re-kill project Chrome PID=$($_.ProcessId)"
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
} else {
    Write-Info "  project Chrome profiles released"
}

Write-Info "[6/6] Verify ports are released"
# re-check ports once
Start-Sleep -Milliseconds 500
foreach ($port in $Ports) {
    $left = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($left) {
        $pids = $left | Select-Object -ExpandProperty OwningProcess -Unique
        foreach ($procId in $pids) {
            if ($procId -and $procId -ne 0) {
                Write-Info "  [:$port] re-kill PID=$procId"
                Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
            }
        }
    }
}

Write-Info ""
Write-Info "Stop complete."
if (-not $Quiet) {
    Write-Host ""
    if (-not $env:JOBRADAR_NO_PAUSE) { pause }
}
exit 0
