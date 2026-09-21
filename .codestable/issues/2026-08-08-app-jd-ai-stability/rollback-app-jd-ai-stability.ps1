[CmdletBinding()]
param(
    [string]$Root
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Root)) {
    $Root = Join-Path $PSScriptRoot '..\..\..'
}
$rootPath = (Resolve-Path -LiteralPath $Root).Path
$baselineRoot = Join-Path $rootPath '.codestable\features\2026-08-08-app-jd-ai-stability\baseline'
$files = @(
    'src\main\java\com\getjobs\application\service\AiService.java',
    'src\main\java\com\getjobs\application\service\Job51Service.java',
    'src\main\java\com\getjobs\worker\job51\Job51.java',
    'src\main\java\com\getjobs\worker\job51\Job51Locators.java',
    'src\test\java\com\getjobs\application\service\AiPromptTest.java',
    'src\test\java\com\getjobs\worker\job51\Job51BehaviorTest.java'
)

foreach ($relative in $files) {
    $source = Join-Path $baselineRoot $relative
    $destination = Join-Path $rootPath $relative
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "BASELINE_MISSING $relative"
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination -Force
    Write-Output "RESTORED $relative"
}

$newTest = Join-Path $rootPath 'src\test\java\com\getjobs\application\service\Job51SearchJsonTest.java'
if (Test-Path -LiteralPath $newTest -PathType Leaf) {
    Remove-Item -LiteralPath $newTest -Force
    Write-Output 'REMOVED src\test\java\com\getjobs\application\service\Job51SearchJsonTest.java'
}

foreach ($relative in $files) {
    $source = Join-Path $baselineRoot $relative
    $destination = Join-Path $rootPath $relative
    $expected = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    $actual = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
    $match = $expected -eq $actual
    Write-Output "HASH_MATCH $($relative -replace '\\','/')=$match"
    if (-not $match) {
        throw "HASH_MISMATCH $relative"
    }
}

Write-Output "ROLLBACK_ROOT $rootPath"
