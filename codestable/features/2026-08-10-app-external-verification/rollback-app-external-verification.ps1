[CmdletBinding()]
param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
)

$patch = Join-Path $PSScriptRoot 'app-external-verification.patch'
if (-not (Test-Path -LiteralPath $patch)) {
    throw "patch not found: $patch"
}

$check = & git -C $Root apply --reverse --check --ignore-space-change --ignore-whitespace -- $patch 2>&1
if ($LASTEXITCODE -ne 0) {
    $check | Write-Output
    throw 'rollback check failed'
}

& git -C $Root apply --reverse --ignore-space-change --ignore-whitespace -- $patch
if ($LASTEXITCODE -ne 0) {
    throw 'rollback apply failed'
}

Write-Output "ROLLED_BACK=$patch"
