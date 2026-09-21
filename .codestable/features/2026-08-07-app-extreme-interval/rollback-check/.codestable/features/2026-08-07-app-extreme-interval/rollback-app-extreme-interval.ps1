param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path $RepoRoot).Path
$feature = Join-Path $RepoRoot '.codestable\features\2026-08-07-app-extreme-interval'
$baseline = Join-Path $feature 'baseline'

$files = @(
    'front\app\liepin\page.tsx',
    'src\main\java\com\getjobs\application\service\LiepinService.java',
    'src\main\java\com\getjobs\worker\liepin\Liepin.java',
    'src\main\java\com\getjobs\worker\liepin\LiepinConfig.java',
    'src\main\java\com\getjobs\worker\liepin\LiepinRateGuard.java',
    'src\test\java\com\getjobs\worker\liepin\LiepinRateGuardTest.java',
    'src\test\java\com\getjobs\worker\liepin\LiepinConfigTest.java'
)

foreach ($file in $files) {
    $source = Join-Path $baseline $file
    $target = Join-Path $RepoRoot $file
    if (-not (Test-Path -LiteralPath $source)) { throw "Missing baseline: $source" }
    Copy-Item -LiteralPath $source -Destination $target -Force
}

$python = (Get-Command python -ErrorAction Stop).Source
$baselineDb = Join-Path $baseline 'db\getjobs.db'
$liveDb = Join-Path $RepoRoot 'db\getjobs.db'
$rollbackSource = @'
import sqlite3
import sys

baseline, live = sys.argv[1:]
source = sqlite3.connect("file:" + baseline.replace("\\", "/") + "?mode=ro", uri=True)
values = source.execute("SELECT min_delay_seconds,max_delay_seconds,search_min_delay_seconds,search_max_delay_seconds,page_min_delay_seconds,page_max_delay_seconds,detail_min_delay_seconds,detail_max_delay_seconds,rate_guard_batch_size,batch_cooldown_min_seconds,batch_cooldown_max_seconds FROM liepin_config WHERE id=1").fetchone()
source.close()
if values is None:
    raise RuntimeError("baseline config row id=1 missing")

target = sqlite3.connect(live, timeout=10)
target.execute("PRAGMA busy_timeout=10000")
target.execute("BEGIN IMMEDIATE")
target.execute("UPDATE liepin_config SET min_delay_seconds=?,max_delay_seconds=?,search_min_delay_seconds=?,search_max_delay_seconds=?,page_min_delay_seconds=?,page_max_delay_seconds=?,detail_min_delay_seconds=?,detail_max_delay_seconds=?,rate_guard_batch_size=?,batch_cooldown_min_seconds=?,batch_cooldown_max_seconds=? WHERE id=1", values)
if target.execute("SELECT changes()").fetchone()[0] != 1:
    target.rollback()
    raise RuntimeError("rollback updated rows != 1")
target.commit()
print("ROLLBACK_DB_OK", values)
target.close()
'@
$scriptFile = Join-Path $env:TEMP ("get-jobs-rollback-" + [guid]::NewGuid().ToString('N') + '.py')
try {
    [IO.File]::WriteAllText($scriptFile, $rollbackSource, (New-Object System.Text.UTF8Encoding($false)))
    $output = & $python $scriptFile $baselineDb $liveDb 2>&1
    $exitCode = $LASTEXITCODE
    $output
    if ($exitCode -ne 0 -or -not ($output -match 'ROLLBACK_DB_OK')) {
        throw "Database rollback failed with exit code $exitCode"
    }
} finally {
    Remove-Item -LiteralPath $scriptFile -Force -ErrorAction SilentlyContinue
}

Write-Output 'ROLLBACK_SOURCE_OK'
