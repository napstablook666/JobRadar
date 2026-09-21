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

$feature = Join-Path $Root '.codestable\features\2026-08-06-app-delivery-stall'
$targets = @(
    @{ Source = 'src\main\java\com\getjobs\worker\job51\Job51.java'; Baseline = 'Job51.java.baseline' },
    @{ Source = 'src\main\java\com\getjobs\worker\service\Job51JobService.java'; Baseline = 'Job51JobService.java.baseline' },
    @{ Source = 'src\main\java\com\getjobs\worker\manager\PlaywrightManager.java'; Baseline = 'PlaywrightManager.java.baseline' }
)

foreach ($target in $targets) {
    $sourcePath = Join-Path $Root $target.Source
    $baselinePath = Join-Path $feature $target.Baseline
    if (-not (Test-Path -LiteralPath $baselinePath)) {
        throw "Missing baseline: $baselinePath"
    }
    Copy-Item -LiteralPath $baselinePath -Destination $sourcePath -Force
    Write-Output "RESTORED $($target.Source)"
}

$testPath = Join-Path $Root 'src\test\java\com\getjobs\worker\job51\Job51BehaviorTest.java'
if (Test-Path -LiteralPath $testPath) {
    Remove-Item -LiteralPath $testPath -Force
    Write-Output 'REMOVED src\test\java\com\getjobs\worker\job51\Job51BehaviorTest.java'
}

Write-Output "ROLLBACK_ROOT $Root"
