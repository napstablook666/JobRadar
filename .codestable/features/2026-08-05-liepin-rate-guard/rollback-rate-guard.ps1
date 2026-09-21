$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$patch = Join-Path $PSScriptRoot 'rate-guard.patch'

if (-not (Test-Path -LiteralPath $patch)) {
    throw "Patch not found: $patch"
}

Push-Location $root
try {
    git apply --reverse --ignore-space-change --ignore-whitespace --check $patch
    git apply --reverse --ignore-space-change --ignore-whitespace $patch
    Write-Output 'Rollback completed: rate guard changes removed; pre-existing worktree changes preserved.'
} finally {
    Pop-Location
}
