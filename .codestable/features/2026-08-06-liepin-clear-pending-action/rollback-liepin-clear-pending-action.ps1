param(
    [string]$RootPath = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
)

$ErrorActionPreference = "Stop"
$RootPath = (Resolve-Path $RootPath).Path

function Remove-MarkedBlock([string]$path, [string]$marker) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing rollback target: $path"
    }

    $lines = [System.IO.File]::ReadAllLines($path)
    $result = New-Object System.Collections.Generic.List[string]
    $inside = $false
    $begin = "BEGIN feature: $marker"
    $end = "END feature: $marker"

    foreach ($line in $lines) {
        if (-not $inside -and $line.Contains($begin)) {
            $inside = $true
            continue
        }
        if ($inside -and $line.Contains($end)) {
            $inside = $false
            continue
        }
        if (-not $inside) {
            $result.Add($line)
        }
    }

    if ($inside) {
        throw "Unclosed rollback marker '$marker' in $path"
    }

    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($path, $result, $utf8)
}

function Replace-Once([string]$path, [string]$from, [string]$to) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing rollback target: $path"
    }
    $text = [System.IO.File]::ReadAllText($path)
    if (-not $text.Contains($from)) {
        throw "Rollback text not found in $path"
    }
    $text = $text.Replace($from, $to)
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, $text, $utf8)
}

function Replace-RegexOnce([string]$path, [string]$pattern, [string]$to) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing rollback target: $path"
    }
    $text = [System.IO.File]::ReadAllText($path)
    $regex = New-Object System.Text.RegularExpressions.Regex(
        $pattern,
        [System.Text.RegularExpressions.RegexOptions]::Singleline
    )
    $matches = $regex.Matches($text)
    if ($matches.Count -ne 1) {
        throw "Expected one rollback regex match in $path, got $($matches.Count)"
    }
    $text = $regex.Replace($text, $to, 1)
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, $text, $utf8)
}

$service = Join-Path $RootPath "src\main\java\com\getjobs\application\service\LiepinService.java"
$controller = Join-Path $RootPath "src\main\java\com\getjobs\application\controller\LiepinController.java"
$analysis = Join-Path $RootPath "front\app\liepin\analysis\AnalysisContent.tsx"
$liepinPage = Join-Path $RootPath "front\app\liepin\page.tsx"
$progress = Join-Path $RootPath "进度白板.md"

Remove-MarkedBlock $service "liepin-clear-pending-action"
Remove-MarkedBlock $controller "liepin-clear-pending-action"
Remove-MarkedBlock $analysis "liepin-clear-pending-action"

Replace-Once $analysis ', BiTrash' ''
Replace-RegexOnce $analysis '(?s)export default function AnalysisContent\(\{\s*showHeader = false,\s*deliveryRunning = false,\s*\}: \{\s*showHeader\?: boolean\s*deliveryRunning\?: boolean\s*\}\) \{' 'export default function AnalysisContent({ showHeader = false }: { showHeader?: boolean }) {'
Replace-RegexOnce $analysis '(?m)^\s*loadPendingSummary\(\)\s*$' ''
Replace-Once $liepinPage '<AnalysisContent deliveryRunning={isDelivering} />' '<AnalysisContent />'

$newTestFiles = @(
    "src\test\java\com\getjobs\application\service\LiepinServicePendingCleanupTest.java",
    "src\test\java\com\getjobs\application\controller\LiepinControllerPendingCleanupTest.java"
)
foreach ($relativePath in $newTestFiles) {
    $testPath = Join-Path $RootPath $relativePath
    if (Test-Path -LiteralPath $testPath) {
        Remove-Item -LiteralPath $testPath -Force
    }
}

if (Test-Path -LiteralPath $progress) {
    Replace-RegexOnce $progress '(?m)^✅ 已完成：猎聘一键清除未投递岗位[^\r\n]*(?:\r?\n|$)' ''
    Replace-RegexOnce $progress '(?m)^🔨 正在做：运行态接口验证[^\r\n]*(?:\r?\n|$)' "🔨 正在做：无。`r`n"
    Replace-RegexOnce $progress '(?m)^⬜ 还没做：后端重载后的真实接口回归。[^\r\n]*(?:\r?\n|$)' ''
}

Write-Output "ROLLBACK_OK"
