[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('inspect', 'checkpoint', 'commit', 'rollback')]
    [string]$Action = 'inspect',
    [string]$Label = 'task',
    [string]$CheckpointPath,
    [string]$Message,
    [string]$Commit,
    [string[]]$Path,
    [switch]$Confirm
)

$ErrorActionPreference = 'Stop'

function Invoke-GitText {
    param([string[]]$Arguments)
    $output = & git @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ("git {0}`n{1}" -f ($Arguments -join ' '), ($output -join "`n"))
    }
    return @($output)
}

function Get-RepoRoot {
    return (Invoke-GitText @('rev-parse', '--show-toplevel') | Select-Object -First 1).Trim()
}

function Get-RelativePath {
    param([string]$Candidate)
    $inputPath = if ([IO.Path]::IsPathRooted($Candidate)) { $Candidate } else { Join-Path $script:RepoRoot $Candidate }
    $full = [IO.Path]::GetFullPath($inputPath)
    $root = [IO.Path]::GetFullPath($script:RepoRoot).TrimEnd('\') + '\'
    if (-not $full.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) {
        throw "path outside repository: $Candidate"
    }
    return $full.Substring($root.Length).Replace('\', '/')
}

function Assert-AllowedPath {
    param([string]$RelativePath)
    $lower = $RelativePath.ToLowerInvariant()
    $blocked = $lower.Contains('/target/') -or $lower.Contains('/db/') -or
        $lower.Contains('/browser-data/') -or $lower.EndsWith('/.env') -or
        $lower.Contains('/.env.') -or $lower.EndsWith('/cookie.json') -or
        $lower.EndsWith('/auth.json') -or $lower.EndsWith('.db') -or
        $lower.EndsWith('.log') -or $lower.EndsWith('.sqlite')
    if ($blocked) {
        throw "refusing sensitive/generated path: $RelativePath"
    }
}

function Get-StatusPaths {
    $lines = Invoke-GitText @('status', '--porcelain=v1', '--untracked-files=all')
    foreach ($line in $lines) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.Length -lt 4) { continue }
        $path = $line.Substring(3).Trim()
        if ($path -match ' -> ') { $path = ($path -split ' -> ')[-1] }
        $path.Replace('\', '/')
    }
}

function Get-SnapshotPath {
    param([string]$Candidate)
    if (-not $Candidate) { throw 'CheckpointPath is required' }
    $full = [IO.Path]::GetFullPath($Candidate)
    if (-not (Test-Path -LiteralPath (Join-Path $full 'baseline-status.txt'))) {
        throw "invalid checkpoint: $full"
    }
    return $full
}

$script:RepoRoot = Get-RepoRoot
Set-Location -LiteralPath $script:RepoRoot
$store = Join-Path $script:RepoRoot 'target\codex-git\checkpoints'

switch ($Action) {
    'inspect' {
        Write-Output (Invoke-GitText @('status', '--short', '--branch'))
        Write-Output ("HEAD=" + (Invoke-GitText @('rev-parse', '--short', 'HEAD') | Select-Object -First 1))
        Write-Output ("REMOTE=" + (Invoke-GitText @('rev-parse', '--abbrev-ref', '--symbolic-full-name', '@{upstream}') | Select-Object -First 1))
        break
    }
    'checkpoint' {
        $safeLabel = ($Label -replace '[^A-Za-z0-9._-]', '-')
        $path = Join-Path $store ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $safeLabel)
        New-Item -ItemType Directory -Path $path -Force | Out-Null
        Invoke-GitText @('rev-parse', 'HEAD') | Set-Content -LiteralPath (Join-Path $path 'baseline-head.txt') -Encoding UTF8
        Invoke-GitText @('status', '--porcelain=v1', '--untracked-files=all') | Set-Content -LiteralPath (Join-Path $path 'baseline-status.txt') -Encoding UTF8
        Invoke-GitText @('ls-files', '--others', '--exclude-standard') | Set-Content -LiteralPath (Join-Path $path 'baseline-untracked.txt') -Encoding UTF8
        (& git diff --binary -- .) | Set-Content -LiteralPath (Join-Path $path 'working-tree.diff') -Encoding UTF8
        (& git diff --cached --binary -- .) | Set-Content -LiteralPath (Join-Path $path 'index.diff') -Encoding UTF8
        Write-Output $path
        break
    }
    'commit' {
        $snapshot = Get-SnapshotPath $CheckpointPath
        if ([string]::IsNullOrWhiteSpace($Message)) { throw 'Message is required' }
        if (-not $Path -or $Path.Count -eq 0) { throw 'at least one -Path is required' }
        $baseline = @(Get-Content -LiteralPath (Join-Path $snapshot 'baseline-status.txt') -Encoding UTF8)
        $baselinePaths = @($baseline | ForEach-Object {
            if ($_.Length -ge 4) { $p = $_.Substring(3).Trim(); if ($p -match ' -> ') { $p = ($p -split ' -> ')[-1] }; $p.Replace('\', '/') }
        })
        $paths = @($Path | ForEach-Object { $p = Get-RelativePath $_; Assert-AllowedPath $p; $p })
        $preexisting = @($paths | Where-Object { $baselinePaths -contains $_ })
        if ($preexisting.Count -gt 0) { throw ("refusing pre-existing paths: " + ($preexisting -join ', ')) }
        Invoke-GitText (@('add', '--') + $paths) | Out-Null
        $staged = @(Invoke-GitText @('diff', '--cached', '--name-only', '--') | ForEach-Object { $_.Trim().Replace('\', '/') } | Where-Object { $_ })
        if ($staged.Count -eq 0) { throw 'no staged changes after path filtering' }
        Invoke-GitText @('diff', '--cached', '--check') | Out-Null
        Invoke-GitText @('commit', '-m', $Message) | Write-Output
        Write-Output ("COMMIT=" + (Invoke-GitText @('rev-parse', '--short', 'HEAD') | Select-Object -First 1))
        break
    }
    'rollback' {
        if (-not $Confirm) { throw 'add -Confirm to create a revert commit' }
        if ([string]::IsNullOrWhiteSpace($Commit)) { throw 'Commit is required' }
        $dirty = @(Invoke-GitText @('status', '--porcelain') | Where-Object { $_.Trim() })
        if ($dirty.Count -gt 0) { throw 'working tree is dirty; preserve it before rollback' }
        Invoke-GitText @('revert', '--no-edit', $Commit) | Write-Output
        Write-Output ("REVERT=" + (Invoke-GitText @('rev-parse', '--short', 'HEAD') | Select-Object -First 1))
        break
    }
}
