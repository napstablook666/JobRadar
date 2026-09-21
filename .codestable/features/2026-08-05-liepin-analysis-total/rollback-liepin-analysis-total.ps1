[CmdletBinding()]
param(
    [string]$Root
)

$featurePath = '.codestable/features/2026-08-05-liepin-analysis-total'
if ([string]::IsNullOrWhiteSpace($Root)) {
    $Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
} else {
    $Root = (Resolve-Path $Root).Path
}

$entries = @(
    @{ Target = 'front/app/liepin/analysis/AnalysisContent.tsx'; Baseline = 'AnalysisContent.tsx.baseline'; Expected = 'D4538B1E5BCBEC197BA5860A02E9152DE71163C8298D78EB4ECA618DC3BD45E4' },
    @{ Target = '进度白板.md'; Baseline = '进度白板.md.baseline'; Expected = '60EA54BF48604FE0E6D20757A48AD5ADBE0715E042E353E38962F901135E8A27' }
)

foreach ($entry in $entries) {
    $target = Join-Path $Root $entry.Target
    $baseline = Join-Path (Join-Path $Root $featurePath) $entry.Baseline
    if (!(Test-Path -LiteralPath $target) -or !(Test-Path -LiteralPath $baseline)) {
        throw "Rollback input missing: $($entry.Target)"
    }
    $actual = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($actual -ne $entry.Expected) {
        throw "Target changed after this fix; refusing rollback: $($entry.Target)"
    }
    Copy-Item -LiteralPath $baseline -Destination $target -Force
}

Write-Output 'Rollback completed: restored the analysis page baseline and progress board.'
