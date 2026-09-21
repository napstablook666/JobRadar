param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
)

$ErrorActionPreference = "Stop"

function Write-CrlfText([string]$path, [string]$content) {
    $content = $content -replace "`r?`n", "`r`n"
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, $content, $encoding)
}

function Replace-Once([string]$path, [string]$old, [string]$new) {
    $text = [System.IO.File]::ReadAllText($path).Replace("`r`n", "`n")
    $old = $old.Replace("`r`n", "`n")
    $new = $new.Replace("`r`n", "`n")
    $count = ([regex]::Matches($text, [regex]::Escape($old))).Count
    if ($count -ne 1) { throw "rollback marker count mismatch: $path expected 1, got $count" }
    Write-CrlfText $path ($text.Replace($old, $new))
}

function Replace-All([string]$path, [string]$old, [string]$new) {
    $text = [System.IO.File]::ReadAllText($path).Replace("`r`n", "`n")
    Write-CrlfText $path ($text.Replace($old.Replace("`r`n", "`n"), $new.Replace("`r`n", "`n")))
}

$rootBatFiles = @("start.bat", "stop.bat", "一键启动.bat", "一键停止.bat")
foreach ($name in $rootBatFiles) {
    $path = Join-Path $Root $name
    Replace-Once $path "setlocal EnableExtensions" "setlocal"
    Replace-Once $path "powershell.exe" "powershell"
    Replace-Once $path 'set "ERR=%ERRORLEVEL%"' "set ERR=%ERRORLEVEL%"
}

$killPath = Join-Path $Root "bin\kill-services.bat"
Write-CrlfText $killPath @"
@echo off
cd /d "%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%CD%\bin\stop-services.ps1" %*
"@

$startPath = Join-Path $Root "bin\start-services.ps1"
$tcpFunction = @'
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
'@
Replace-Once $startPath $tcpFunction ""
Replace-Once $startPath '$pmCmd = "call pnpm.cmd"' '$pmCmd = "pnpm"'
Replace-Once $startPath '$pmCmd = "call npx.cmd --yes pnpm"' '$pmCmd = "npx pnpm"'
Replace-All $startPath '"/d", "/c", "call",' '"/c", "call",'
Replace-Once $startPath 'if (Test-TcpPort "127.0.0.1" 8888) {' 'if (Test-HttpOk "http://127.0.0.1:8888/") {'
Replace-Once $startPath 'Write-Step "[OK] backend port ready (~${i}s) log: $BackLog"' 'Write-Step "[OK] backend ready (~${i}s) log: $BackLog"'

$stopPath = Join-Path $Root "bin\stop-services.ps1"
Replace-Once $stopPath 'if ($normCl.IndexOf($normRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0) { return $true }' 'if ($normCl.Contains($normRoot)) { return $true }'

$boardPath = Join-Path $Root "进度白板.md"
Replace-Once $boardPath "✅ 已完成：BAT 启动脚本适配 —— 从任意工作目录一键启动前端 6866 和后端 8888，支持 JDK 21、中文空格路径、日志和参数转发。`n" ""
Replace-Once $boardPath "✅ 已完成：BAT 启停验证 —— 启动返回 0、前端 HTTP 200、后端端口就绪，停止后 6866/8888 端口释放。`n" ""

Write-Output "ROLLBACK_OK root=$Root"
