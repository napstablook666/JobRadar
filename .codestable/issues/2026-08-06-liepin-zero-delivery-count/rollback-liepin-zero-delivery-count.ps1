param(
    [string]$Root
)

$ErrorActionPreference = 'Stop'
$issue = '.codestable\issues\2026-08-06-liepin-zero-delivery-count'
$scriptRoot = (Resolve-Path -LiteralPath $PSScriptRoot).Path
if ([string]::IsNullOrWhiteSpace($Root)) {
    $Root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $scriptRoot))
}
$Root = (Resolve-Path -LiteralPath $Root).Path
$rootWithSep = $Root.TrimEnd('\') + '\'

function Resolve-WorkspacePath([string]$relative) {
    $target = [IO.Path]::GetFullPath((Join-Path $Root $relative))
    if (-not $target.StartsWith($rootWithSep, [StringComparison]::OrdinalIgnoreCase)) {
        throw "回滚路径越界: $target"
    }
    return $target
}

$restore = @{
    'src\main\java\com\getjobs\worker\liepin\Liepin.java' = "$issue\baseline\src__main__java__com__getjobs__worker__liepin__Liepin.java"
    'src\main\java\com\getjobs\worker\service\LiepinJobService.java' = "$issue\baseline\src__main__java__com__getjobs__worker__service__LiepinJobService.java"
    '进度白板.md' = "$issue\baseline\进度白板.md"
}

foreach ($entry in $restore.GetEnumerator()) {
    $target = Resolve-WorkspacePath $entry.Key
    $source = Resolve-WorkspacePath $entry.Value
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "回滚基线缺失: $source"
    }
    Copy-Item -LiteralPath $source -Destination $target -Force
}

$newTest = Resolve-WorkspacePath 'src\test\java\com\getjobs\worker\service\LiepinJobServiceDeliveryTest.java'
if (Test-Path -LiteralPath $newTest -PathType Leaf) {
    Remove-Item -LiteralPath $newTest -Force
}

foreach ($entry in $restore.GetEnumerator()) {
    $target = Resolve-WorkspacePath $entry.Key
    $source = Resolve-WorkspacePath $entry.Value
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash -ne (Get-FileHash -Algorithm SHA256 -LiteralPath $source).Hash) {
        throw "回滚校验失败: $target"
    }
}
if (Test-Path -LiteralPath $newTest) {
    throw "新增测试仍存在: $newTest"
}

Write-Output 'ROLLBACK_OK'
