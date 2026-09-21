$ErrorActionPreference = 'Stop'

$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$baseline = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'baseline\getjobs.db'))
$target = [System.IO.Path]::GetFullPath((Join-Path $root 'db\getjobs.db'))
$rootPrefix = $root.TrimEnd('\') + '\'

if (-not $baseline.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'BASELINE_OUTSIDE_WORKSPACE'
}
if (-not $target.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'TARGET_OUTSIDE_WORKSPACE'
}
if (-not (Test-Path -LiteralPath $baseline)) {
    throw "BASELINE_MISSING: $baseline"
}

$env:GETJOBS_NO_PAUSE = '1'
$stopScript = Join-Path $root 'bin\stop-services.ps1'
if (Test-Path -LiteralPath $stopScript) {
    & $stopScript -Quiet
}

$temp = "$target.rollback.tmp"
Copy-Item -LiteralPath $baseline -Destination $temp -Force
$expected = (Get-FileHash -Algorithm SHA256 -LiteralPath $baseline).Hash
$actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $temp).Hash
if ($expected -ne $actual) {
    Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue
    throw 'ROLLBACK_COPY_HASH_MISMATCH'
}
Move-Item -LiteralPath $temp -Destination $target -Force

$restored = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash
if ($restored -ne $expected) {
    throw 'ROLLBACK_TARGET_HASH_MISMATCH'
}
Write-Output 'ROLLBACK_OK'
Write-Output "RESTORED_SHA256=$restored"
