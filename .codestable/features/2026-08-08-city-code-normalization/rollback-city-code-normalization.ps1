param(
    [string]$RepositoryRoot
)

$ErrorActionPreference = "Stop"

if ($RepositoryRoot) {
    $Root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
} else {
    $Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..\..\..")).Path
}

$FeatureRoot = Join-Path $Root ".codestable\features\2026-08-08-city-code-normalization"
$files = @(
    @("baseline\src\main\java\com\getjobs\application\service\ConfigService.java", "src\main\java\com\getjobs\application\service\ConfigService.java"),
    @("baseline\src\main\java\com\getjobs\application\service\LiepinService.java", "src\main\java\com\getjobs\application\service\LiepinService.java"),
    @("baseline\src\main\java\com\getjobs\application\controller\LiepinController.java", "src\main\java\com\getjobs\application\controller\LiepinController.java")
)

foreach ($pair in $files) {
    $source = Join-Path $FeatureRoot $pair[0]
    $target = Join-Path $Root $pair[1]
    if (-not (Test-Path -LiteralPath $source)) {
        throw "Rollback baseline missing: $source"
    }
    Copy-Item -LiteralPath $source -Destination $target -Force
}

$newTests = @(
    "src\test\java\com\getjobs\application\service\LiepinCityNormalizationTest.java",
    "src\test\java\com\getjobs\application\service\ConfigServiceCityConfigTest.java",
    "src\test\java\com\getjobs\application\controller\LiepinControllerCityConfigTest.java"
)
foreach ($relativePath in $newTests) {
    $target = Join-Path $Root $relativePath
    if (Test-Path -LiteralPath $target) {
        Remove-Item -LiteralPath $target -Force
    }
}

Write-Output "Rollback complete for city-code normalization."
