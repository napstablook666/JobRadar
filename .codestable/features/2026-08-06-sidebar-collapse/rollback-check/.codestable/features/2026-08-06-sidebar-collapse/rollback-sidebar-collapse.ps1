$ErrorActionPreference = 'Stop'

$featureRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $featureRoot '..\..\..')
$baselineRoot = Join-Path $featureRoot 'baseline'

$files = @(
  @{ Baseline = 'layout.tsx'; Destination = 'front\app\layout.tsx' },
  @{ Baseline = 'Sidebar.tsx'; Destination = 'front\app\components\Sidebar.tsx' },
  @{ Baseline = 'ContentArea.tsx'; Destination = 'front\app\components\ContentArea.tsx' },
  @{ Baseline = '进度白板.md'; Destination = '进度白板.md' }
)

foreach ($file in $files) {
  Copy-Item -LiteralPath (Join-Path $baselineRoot $file.Baseline) `
    -Destination (Join-Path $repoRoot $file.Destination) -Force
}

Write-Output 'Rollback complete: sidebar collapse files restored from baseline.'
