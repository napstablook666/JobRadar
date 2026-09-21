# GetJobs stop services
param(
    [switch]$Quiet
)

$ErrorActionPreference = "SilentlyContinue"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Front = Join-Path $Root "front"
$Ports = @(6866, 6868, 8888, 7866)

function Write-Info([string]$msg) {
    if (-not $Quiet) { Write-Host $msg }
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
Write-Info " GetJobs stop"
Write-Info " Root: $Root"
Write-Info "==============================================="
Write-Info ""

Write-Info "[1/4] Kill listeners on ports: $($Ports -join ', ')"
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
Write-Info "[2/4] Kill GetJobs-Front / GetJobs-Backend console windows"
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
        ($_.CommandLine -match 'GetJobs-Front|GetJobs-Backend|run-front\.bat|run-backend\.bat') -and
        ($protect -notcontains $_.ProcessId)
    } |
    ForEach-Object {
        Write-Info "  kill window PID=$($_.ProcessId) Name=$($_.Name)"
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }

Write-Info ""
Write-Info "[3/4] Kill Java bootRun / Gradle for this repo + Next frontend"
Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine } |
    ForEach-Object {
        $cl = $_.CommandLine
        $name = $_.Name
        $kill = $false
        if ($name -match 'java|javaw') {
            if ($cl -match 'GetJobsApplication|bootRun') { $kill = $true }
            elseif ($cl -match 'gradle' -and (Test-CmdTouchesRoot $cl $Root)) { $kill = $true }
            elseif ((Test-CmdTouchesRoot $cl $Root) -and $cl -match 'get_jobs|get-jobs|GetJobs') { $kill = $true }
        }
        if ($name -match 'node' -and $cl -match 'next' -and (Test-CmdTouchesRoot $cl $Front)) { $kill = $true }
        if ($kill) {
            Write-Info "  kill PID=$($_.ProcessId) Name=$name"
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
    }

Write-Info ""
Write-Info "[4/4] Kill project Chrome CDP (7866 / chrome-cdp-profile)"
Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object {
        $_.Name -match 'chrome|chromium' -and
        $_.CommandLine -and (
            $_.CommandLine -like '*remote-debugging-port=7866*' -or
            $_.CommandLine -like '*chrome-cdp-profile*'
        )
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
    if (-not $env:GETJOBS_NO_PAUSE) { pause }
}
exit 0
