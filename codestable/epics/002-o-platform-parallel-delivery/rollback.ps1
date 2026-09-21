param(
    [string]$RepositoryRoot = '',
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
$scriptRoot = (Resolve-Path $PSScriptRoot).Path
$patchPath = Join-Path $scriptRoot 'delivery-runtime-fallback.patch'

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = (Resolve-Path (Join-Path $scriptRoot '..\..\..')).Path
} else {
    $RepositoryRoot = (Resolve-Path $RepositoryRoot).Path
}

$trackedFiles = @(
    'src/main/java/com/getjobs/worker/service/Job51JobService.java',
    'src/main/java/com/getjobs/worker/service/BossJobService.java',
    'src/main/java/com/getjobs/worker/service/ZhilianJobService.java',
    'src/main/java/com/getjobs/worker/service/LiepinJobService.java'
)
$lineEndings = @{}
foreach ($relativePath in $trackedFiles) {
    $path = Join-Path $RepositoryRoot $relativePath
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $lineEndings[$relativePath] = "`n"
    for ($index = 1; $index -lt $bytes.Length; $index++) {
        if ($bytes[$index] -eq 10) {
            $lineEndings[$relativePath] = if ($bytes[$index - 1] -eq 13) { "`r`n" } else { "`n" }
            break
        }
    }
}

function Restore-LineEndings {
    foreach ($relativePath in $trackedFiles) {
        $path = Join-Path $RepositoryRoot $relativePath
        $text = [System.IO.File]::ReadAllText($path)
        $normalized = $text.Replace("`r`n", "`n").Replace("`r", "`n")
        if ($lineEndings[$relativePath] -eq "`r`n") {
            $normalized = $normalized.Replace("`n", "`r`n")
        }
        [System.IO.File]::WriteAllText(
            $path,
            $normalized,
            [System.Text.UTF8Encoding]::new($false)
        )
    }
}

Push-Location $RepositoryRoot
try {
    if ($Check) {
        & git apply --reverse --check -- $patchPath
        if ($LASTEXITCODE -ne 0) {
            throw "反向 patch 检查失败，工作区可能已经回滚或发生漂移"
        }
        Write-Output 'rollback check passed'
        exit 0
    }

    & git apply --reverse -- $patchPath
    if ($LASTEXITCODE -ne 0) {
        throw '反向 patch 应用失败'
    }
    Restore-LineEndings
    Write-Output 'rollback applied'
} finally {
    Pop-Location
}
