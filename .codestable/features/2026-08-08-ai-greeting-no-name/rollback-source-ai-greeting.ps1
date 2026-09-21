param(
    [string]$Root = (Join-Path $PSScriptRoot 'patch-check-2')
)

$ErrorActionPreference = 'Stop'
$patch = (Resolve-Path (Join-Path $PSScriptRoot 'ai-greeting-no-name.patch')).Path
$targetRoot = (Resolve-Path $Root).Path
$gitRoot = (git -C $targetRoot rev-parse --show-toplevel 2>$null)
if ([string]::IsNullOrWhiteSpace($gitRoot)) {
    throw "未找到 Git 工作树: $targetRoot"
}
$gitRoot = (Resolve-Path $gitRoot.Trim()).Path
$prefix = $gitRoot.TrimEnd('\') + '\'
if (-not $targetRoot.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "回滚目录必须位于仓库内: $targetRoot"
}
$directory = $targetRoot.Substring($gitRoot.Length + 1).Replace('\', '/')

Push-Location $gitRoot
try {
    & git apply --reverse --check --ignore-whitespace --directory=$directory $patch
    if ($LASTEXITCODE -ne 0) {
        throw "源代码反向补丁预检失败，退出码: $LASTEXITCODE"
    }
    & git apply --reverse --ignore-whitespace --directory=$directory $patch
    if ($LASTEXITCODE -ne 0) {
        throw "源代码回滚失败，退出码: $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

Write-Output 'SOURCE_ROLLBACK_OK'
