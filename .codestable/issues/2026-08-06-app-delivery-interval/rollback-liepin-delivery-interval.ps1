[CmdletBinding()]
param(
    [string]$Root
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Root)) {
    $Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
} else {
    $Root = (Resolve-Path -LiteralPath $Root).Path
}

$unit = Join-Path $Root '.codestable\issues\2026-08-06-app-delivery-interval'
$targets = @(
    @{ Source = 'src\main\java\com\getjobs\worker\liepin\LiepinRateGuard.java'; Baseline = 'baseline\LiepinRateGuard.java' },
    @{ Source = 'src\test\java\com\getjobs\worker\liepin\LiepinRateGuardTest.java'; Baseline = 'baseline\LiepinRateGuardTest.java' }
)

foreach ($target in $targets) {
    $sourcePath = Join-Path $Root $target.Source
    $baselinePath = Join-Path $unit $target.Baseline
    if (-not (Test-Path -LiteralPath $baselinePath)) {
        throw "Missing baseline: $baselinePath"
    }
    Copy-Item -LiteralPath $baselinePath -Destination $sourcePath -Force
    Write-Output "RESTORED $($target.Source)"
}

Write-Output "ROLLBACK_ROOT $Root"
