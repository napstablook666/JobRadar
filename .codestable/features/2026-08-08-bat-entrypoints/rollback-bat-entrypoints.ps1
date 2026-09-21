param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path $Root).Path

function Write-Utf8Crlf([string]$Path, [string]$Text) {
    $lf = [string][char]10
    $crlf = [string][char]13 + [string][char]10
    $normalized = [regex]::Replace($Text, "\r\n?", $lf)
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $normalized.Replace($lf, $crlf), $encoding)
}

function Replace-Once([string]$Path, [string]$Old, [string]$New) {
    $text = [System.IO.File]::ReadAllText($Path)
    $count = ([regex]::Matches($text, [regex]::Escape($Old))).Count
    if ($count -ne 1) {
        throw "rollback marker count mismatch: $Path expected 1, got $count"
    }
    Write-Utf8Crlf $Path ($text.Replace($Old, $New))
}

function Remove-Section([string]$Path, [string]$Marker) {
    $text = [System.IO.File]::ReadAllText($Path)
    $index = $text.IndexOf($Marker, [System.StringComparison]::Ordinal)
    if ($index -lt 0) {
        throw "rollback section marker missing: $Path"
    }
    $prefix = $text.Substring(0, $index).TrimEnd([char]13, [char]10)
    Write-Utf8Crlf $Path ($prefix + [string][char]13 + [string][char]10)
}

$baselineRoot = Join-Path $PSScriptRoot "baseline"
$startBaseline = Join-Path $baselineRoot "start.bat"
$stopBaseline = Join-Path $baselineRoot "stop.bat"
if (-not (Test-Path -LiteralPath $startBaseline) -or -not (Test-Path -LiteralPath $stopBaseline)) {
    throw "entrypoint baseline files are missing under $Root"
}

Copy-Item -LiteralPath $startBaseline -Destination (Join-Path $Root "start.bat") -Force
Copy-Item -LiteralPath $stopBaseline -Destination (Join-Path $Root "stop.bat") -Force
Copy-Item -LiteralPath $startBaseline -Destination (Join-Path $Root "一键启动.bat") -Force
Copy-Item -LiteralPath $stopBaseline -Destination (Join-Path $Root "一键停止.bat") -Force

$restartBat = Join-Path $Root "restart.bat"
if (Test-Path -LiteralPath $restartBat) {
    Remove-Item -LiteralPath $restartBat -Force
}

$startServices = Join-Path $Root "bin\start-services.ps1"
Replace-Once $startServices 'Write-Host " Stop:    double-click stop.bat"' 'Write-Host " Stop:    double-click 一键停止.bat  (or stop.bat)"'
Replace-Once $startServices 'Write-Host " Restart: double-click restart.bat"' ''

$board = Join-Path $Root "进度白板.md"
Remove-Section $board "✅ 已完成：BAT 运维入口三件套"

Write-Output "ROLLBACK_OK root=$Root"
