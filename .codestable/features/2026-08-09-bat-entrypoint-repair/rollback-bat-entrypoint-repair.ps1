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
        throw "Path escaped root: $full"
    }
    return $full
}

$entries = @(
    @{ Baseline = "start.bat"; Target = "start.bat" },
    @{ Baseline = "stop.bat"; Target = "stop.bat" },
    @{ Baseline = "restart.bat"; Target = "restart.bat" },
    @{ Baseline = "进度白板.md"; Target = "进度白板.md" }
)

foreach ($entry in $entries) {
    $baseline = Resolve-ContainedPath $baselineRoot $entry.Baseline
    $target = Resolve-ContainedPath $targetRoot $entry.Target
    if (-not (Test-Path -LiteralPath $baseline -PathType Leaf)) {
        throw "Baseline file missing: $baseline"
    }
    Copy-Item -LiteralPath $baseline -Destination $target -Force
    $expected = (Get-FileHash -Algorithm SHA256 -LiteralPath $baseline).Hash
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash
    if ($expected -ne $actual) {
        throw "Rollback hash mismatch: $target"
    }
    [pscustomobject]@{
        target = $entry.Target
        sha256 = $actual
        status = "rolled-back"
    } | ConvertTo-Json -Compress
}

Write-Output "rollback verified: targetRoot=$targetRoot"
