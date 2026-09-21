param(
    [switch]$VerifyOnly
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$Patch = Join-Path $PSScriptRoot "liepin-hybrid-read.patch"

Push-Location $Root
try {
    & git apply --reverse --check --ignore-space-change --ignore-whitespace $Patch
    if ($LASTEXITCODE -ne 0) {
        throw "hybrid read rollback check failed"
    }
    if ($VerifyOnly) {
        Write-Output "ROLLBACK_CHECK_OK"
        exit 0
    }

    & git apply --reverse --ignore-space-change --ignore-whitespace $Patch
    if ($LASTEXITCODE -ne 0) {
        throw "hybrid read rollback failed"
    }
    Write-Output "ROLLBACK_APPLIED"
} finally {
    Pop-Location
}
