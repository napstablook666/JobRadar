param(
    [string]$RepositoryRoot
)

$ErrorActionPreference = "Stop"

if ($RepositoryRoot) {
    $Root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
} else {
    $Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..\..\..")).Path
}

$FeatureRoot = Join-Path $Root ".codestable\features\2026-08-09-keyword-mojibake"
$files = @(
    @("baseline\front\app\liepin\page.tsx", "front\app\liepin\page.tsx"),
    @("baseline\front\lib\keyword-lines.ts", "front\lib\keyword-lines.ts"),
    @("baseline\src\main\java\com\getjobs\application\service\LiepinService.java", "src\main\java\com\getjobs\application\service\LiepinService.java"),
    @("baseline\src\main\java\com\getjobs\worker\liepin\LiepinPageProgress.java", "src\main\java\com\getjobs\worker\liepin\LiepinPageProgress.java"),
    @("baseline\src\main\java\com\getjobs\worker\utils\KeywordParser.java", "src\main\java\com\getjobs\worker\utils\KeywordParser.java"),
    @("baseline\src\test\java\com\getjobs\worker\liepin\LiepinPageProgressTest.java", "src\test\java\com\getjobs\worker\liepin\LiepinPageProgressTest.java"),
    @("baseline\src\test\java\com\getjobs\worker\utils\KeywordParserTest.java", "src\test\java\com\getjobs\worker\utils\KeywordParserTest.java"),
    @("baseline\进度白板.md", "进度白板.md")
)

foreach ($pair in $files) {
    $source = Join-Path $FeatureRoot $pair[0]
    $target = Join-Path $Root $pair[1]
    if (-not (Test-Path -LiteralPath $source)) {
        throw "Rollback baseline missing: $source"
    }
    $parent = Split-Path -Parent $target
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    Copy-Item -LiteralPath $source -Destination $target -Force
}

$newFiles = @(
    "src\test\java\com\getjobs\application\service\LiepinServiceKeywordNormalizationTest.java",
    "codestable\issues\013-x-ff-liepin-keyword-mojibake.md"
)
foreach ($relativePath in $newFiles) {
    $target = Join-Path $Root $relativePath
    if (Test-Path -LiteralPath $target) {
        Remove-Item -LiteralPath $target -Force
    }
}

Write-Output "Rollback complete for keyword mojibake."
