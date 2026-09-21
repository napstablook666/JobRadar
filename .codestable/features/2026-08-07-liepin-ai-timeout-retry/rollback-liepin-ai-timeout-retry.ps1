param(
    [string]$BaselineDir = ""
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path

if ([string]::IsNullOrWhiteSpace($BaselineDir)) {
    throw "需要传入 -BaselineDir 指向本次修改前的工作区快照；脚本不会覆盖工作区原有改动。"
}

$baseline = (Resolve-Path $BaselineDir).Path
$files = @(
    "front/app/liepin/page.tsx",
    "src/main/java/com/getjobs/application/controller/LiepinController.java",
    "src/main/java/com/getjobs/application/entity/LiepinConfigEntity.java",
    "src/main/java/com/getjobs/application/service/ConfigService.java",
    "src/main/java/com/getjobs/application/service/LiepinService.java",
    "src/main/java/com/getjobs/worker/liepin/Liepin.java",
    "src/main/java/com/getjobs/worker/liepin/LiepinConfig.java",
    "src/main/java/com/getjobs/worker/service/LiepinJobService.java",
    "src/test/java/com/getjobs/worker/liepin/LiepinConfigTest.java",
    "src/test/java/com/getjobs/worker/liepin/LiepinBatchDeliveryGuardTest.java"
)

foreach ($file in $files) {
    $source = Join-Path $baseline $file
    $target = Join-Path $root $file
    if (-not (Test-Path -LiteralPath $source)) {
        throw "基线文件缺失: $source"
    }
    $targetDir = Split-Path -Parent $target
    New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
    Copy-Item -LiteralPath $source -Destination $target -Force
}

Write-Output "Rollback restored $($files.Count) explicitly listed files from $baseline"
