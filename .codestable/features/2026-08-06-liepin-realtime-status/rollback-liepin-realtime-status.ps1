param(
    [string]$Root = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($Root)) {
    $Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
} else {
    $Root = [System.IO.Path]::GetFullPath($Root)
}

$baseline = Join-Path $PSScriptRoot "baseline"
$files = @(
    @{ Source = "LiepinJobService.java"; Target = "src\main\java\com\getjobs\worker\service\LiepinJobService.java" },
    @{ Source = "page.tsx"; Target = "front\app\liepin\page.tsx" },
    @{ Source = "LiepinJobServiceDeliveryTest.java"; Target = "src\test\java\com\getjobs\worker\service\LiepinJobServiceDeliveryTest.java" },
    @{ Source = "进度白板.md"; Target = "进度白板.md" }
)

New-Item -ItemType Directory -Force -Path $Root | Out-Null
foreach ($file in $files) {
    $destination = Join-Path $Root $file.Target
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
    Copy-Item -LiteralPath (Join-Path $baseline $file.Source) -Destination $destination -Force
}

Write-Output "Rollback completed: restored the realtime-status task baseline."
