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

$baselineLiepin = Resolve-ContainedPath $baselineRoot "Liepin.java"
$baselineBoard = Resolve-ContainedPath $baselineRoot "进度白板.md"
$targetLiepin = Resolve-ContainedPath $targetRoot "src\main\java\com\getjobs\worker\liepin\Liepin.java"
$targetTest = Resolve-ContainedPath $targetRoot "src\test\java\com\getjobs\worker\liepin\LiepinSearchResponseTest.java"
$targetBoard = Resolve-ContainedPath $targetRoot "进度白板.md"

if (-not (Test-Path -LiteralPath $baselineLiepin -PathType Leaf)) {
    throw "Baseline file missing: $baselineLiepin"
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $targetLiepin) | Out-Null
Copy-Item -LiteralPath $baselineLiepin -Destination $targetLiepin -Force

$expected = (Get-FileHash -Algorithm SHA256 -LiteralPath $baselineLiepin).Hash
$actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $targetLiepin).Hash
if ($expected -ne $actual) {
    throw "Liepin rollback hash mismatch: $targetLiepin"
}

if (-not (Test-Path -LiteralPath $baselineBoard -PathType Leaf)) {
    throw "Baseline file missing: $baselineBoard"
}
Copy-Item -LiteralPath $baselineBoard -Destination $targetBoard -Force
$expectedBoard = (Get-FileHash -Algorithm SHA256 -LiteralPath $baselineBoard).Hash
$actualBoard = (Get-FileHash -Algorithm SHA256 -LiteralPath $targetBoard).Hash
if ($expectedBoard -ne $actualBoard) {
    throw "Progress board rollback hash mismatch: $targetBoard"
}

if (Test-Path -LiteralPath $targetTest -PathType Leaf) {
    Remove-Item -LiteralPath $targetTest -Force
}
if (Test-Path -LiteralPath $targetTest) {
    throw "Test rollback did not remove: $targetTest"
}

[pscustomobject]@{
    target = "src/main/java/com/getjobs/worker/liepin/Liepin.java"
    sha256 = $actual
    status = "rolled-back"
} | ConvertTo-Json -Compress
Write-Output "board hash verified: $actualBoard"
Write-Output "test removed: src/test/java/com/getjobs/worker/liepin/LiepinSearchResponseTest.java"
Write-Output "rollback verified: targetRoot=$targetRoot"
