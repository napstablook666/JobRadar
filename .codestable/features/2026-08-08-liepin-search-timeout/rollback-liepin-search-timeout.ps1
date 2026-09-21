param(
    [string]$TargetRoot = "",
    [string]$BaselineRoot = ""
)

$ErrorActionPreference = "Stop"
$featureRoot = (Resolve-Path $PSScriptRoot).Path
$repositoryRoot = (Resolve-Path (Join-Path $featureRoot "..\..\..")).Path

if ([string]::IsNullOrWhiteSpace($TargetRoot)) {
    $TargetRoot = $repositoryRoot
}
if ([string]::IsNullOrWhiteSpace($BaselineRoot)) {
    $BaselineRoot = Join-Path $featureRoot "baseline"
}

$targetRoot = (Resolve-Path $TargetRoot).Path
$baselineRoot = (Resolve-Path $BaselineRoot).Path

function Resolve-ContainedPath([string]$root, [string]$relativePath) {
    $full = [IO.Path]::GetFullPath((Join-Path $root $relativePath))
    $prefix = $root.TrimEnd('\') + '\'
    if (-not $full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "路径越界: $full"
    }
    return $full
}

$entries = @(
    @{ Baseline = "Liepin.java"; Target = "src\main\java\com\getjobs\worker\liepin\Liepin.java" },
    @{ Baseline = "LiepinHttpSearchClient.java"; Target = "src\main\java\com\getjobs\worker\liepin\LiepinHttpSearchClient.java" },
    @{ Baseline = "LiepinHttpSearchClientTest.java"; Target = "src\test\java\com\getjobs\worker\liepin\LiepinHttpSearchClientTest.java" }
)

foreach ($entry in $entries) {
    $baseline = Resolve-ContainedPath $baselineRoot $entry.Baseline
    $target = Resolve-ContainedPath $targetRoot $entry.Target
    if (-not (Test-Path -LiteralPath $baseline -PathType Leaf)) {
        throw "基线文件不存在: $baseline"
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
    Copy-Item -LiteralPath $baseline -Destination $target -Force
    $expected = (Get-FileHash -Algorithm SHA256 -LiteralPath $baseline).Hash
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash
    if ($expected -ne $actual) {
        throw "回滚哈希校验失败: $target"
    }
    [pscustomobject]@{
        target = $entry.Target
        sha256 = $actual
        status = "rolled-back"
    } | ConvertTo-Json -Compress
}

Write-Output "rollback verified: targetRoot=$targetRoot"
