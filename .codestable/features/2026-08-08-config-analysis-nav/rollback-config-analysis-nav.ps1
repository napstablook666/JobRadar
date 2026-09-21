[CmdletBinding()]
param(
  [string]$Root
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Root)) {
  $Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
} else {
  $Root = (Resolve-Path $Root).Path
}

$featureRoot = (Resolve-Path $PSScriptRoot).Path
$baselineRoot = Join-Path $featureRoot 'baseline'
$baselineHashesPath = Join-Path $featureRoot 'baseline-hashes.txt'
$modifiedHashesPath = Join-Path $featureRoot 'modified-hashes.txt'
$targets = @(
  'front/app/51job/page.tsx',
  'front/app/boss/page.tsx',
  'front/app/liepin/page.tsx',
  'front/app/zhilian/page.tsx'
)

function Read-HashManifest([string]$Path) {
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    throw "哈希清单缺失：$Path"
  }

  $manifest = @{}
  foreach ($line in Get-Content -LiteralPath $Path) {
    if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) {
      continue
    }
    $parts = $line -split "`t", 2
    if ($parts.Count -ne 2) {
      throw "哈希清单格式错误：$Path"
    }
    $manifest[$parts[1]] = $parts[0].Trim().ToUpperInvariant()
  }
  return $manifest
}

$baselineHashes = Read-HashManifest $baselineHashesPath
$modifiedHashes = Read-HashManifest $modifiedHashesPath
$rootPrefix = $Root.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar

foreach ($relative in $targets) {
  $target = [System.IO.Path]::GetFullPath((Join-Path $Root $relative))
  $source = [System.IO.Path]::GetFullPath((Join-Path $baselineRoot $relative))
  if (-not $target.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "目标路径越界：$target"
  }
  if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
    throw "目标文件不存在：$target"
  }
  if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
    throw "基线文件不存在：$source"
  }
  if (-not $modifiedHashes.ContainsKey($relative) -or -not $baselineHashes.ContainsKey($relative)) {
    throw "哈希清单缺少目标：$relative"
  }

  $targetHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash
  $baselineHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $source).Hash
  if ($targetHash -ne $modifiedHashes[$relative]) {
    throw "目标文件与修改态哈希不匹配：$relative"
  }
  if ($baselineHash -ne $baselineHashes[$relative]) {
    throw "基线快照与基线哈希不匹配：$relative"
  }
}

foreach ($relative in $targets) {
  $target = [System.IO.Path]::GetFullPath((Join-Path $Root $relative))
  $source = [System.IO.Path]::GetFullPath((Join-Path $baselineRoot $relative))
  Copy-Item -LiteralPath $source -Destination $target -Force
}

foreach ($relative in $targets) {
  $target = [System.IO.Path]::GetFullPath((Join-Path $Root $relative))
  $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash
  if ($actual -ne $baselineHashes[$relative]) {
    throw "回滚哈希校验失败：$relative"
  }
}

Write-Output 'ROLLBACK_OK'
Write-Output 'ROLLBACK_HASHES_OK'
