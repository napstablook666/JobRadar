param(
    [switch]$Preview,
    [string]$RepoRoot
)

$ErrorActionPreference = 'Stop'
$featureName = '2026-08-07-liepin-ai-batch-retry'

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
} else {
    $RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
}

$artifactRoot = Join-Path $RepoRoot ".codestable\features\$featureName"
$baselineRoot = Join-Path $artifactRoot 'baseline'

$files = @(
    @{ Target = 'src\main\java\com\getjobs\worker\service\LiepinJobService.java'; Baseline = 'src__main__java__com__getjobs__worker__service__LiepinJobService.java' },
    @{ Target = 'src\main\java\com\getjobs\application\controller\LiepinController.java'; Baseline = 'src__main__java__com__getjobs__application__controller__LiepinController.java' },
    @{ Target = 'front\app\liepin\page.tsx'; Baseline = 'front__app__liepin__page.tsx' },
    @{ Target = 'src\test\java\com\getjobs\worker\service\LiepinJobServiceDeliveryTest.java'; Baseline = 'src__test__java__com__getjobs__worker__service__LiepinJobServiceDeliveryTest.java' },
    @{ Target = 'src\test\java\com\getjobs\application\controller\LiepinControllerPendingCleanupTest.java'; Baseline = 'src__test__java__com__getjobs__application__controller__LiepinControllerPendingCleanupTest.java' }
)

foreach ($file in $files) {
    $source = Join-Path $baselineRoot $file.Baseline
    $target = Join-Path $RepoRoot $file.Target
    if (!(Test-Path -LiteralPath $source) -or !(Test-Path -LiteralPath $target)) {
        throw "rollback-check: missing file source=$source target=$target"
    }

    $expectedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $source).Hash
    if ($Preview) {
        $currentHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash
        $state = if ($currentHash -eq $expectedHash) { 'already-baseline' } else { 'would-restore' }
        Write-Output "rollback-preview: $state $($file.Target)"
        continue
    }

    Copy-Item -LiteralPath $source -Destination $target -Force
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash
    if ($actualHash -ne $expectedHash) {
        throw "rollback-check: hash mismatch after restore $($file.Target)"
    }
}

if ($Preview) {
    Write-Output 'rollback-preview: PREVIEW_ONLY=1'
} else {
    Write-Output 'rollback-check: restored listed files and hashes match'
}
