param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
)

$ErrorActionPreference = 'Stop'
$featureRoot = (Resolve-Path $PSScriptRoot).Path
$resolvedRepo = [System.IO.Path]::GetFullPath($RepoRoot)
$files = @(
    @{ Relative = 'front/app/liepin/page.tsx'; Baseline = 'baseline/front__app__liepin__page.tsx' },
    @{ Relative = 'src/main/java/com/getjobs/application/entity/LiepinConfigEntity.java'; Baseline = 'baseline/src__main__java__com__getjobs__application__entity__LiepinConfigEntity.java' },
    @{ Relative = 'src/main/java/com/getjobs/application/service/ConfigService.java'; Baseline = 'baseline/src__main__java__com__getjobs__application__service__ConfigService.java' },
    @{ Relative = 'src/main/java/com/getjobs/application/service/LiepinService.java'; Baseline = 'baseline/src__main__java__com__getjobs__application__service__LiepinService.java' },
    @{ Relative = 'src/main/java/com/getjobs/worker/liepin/Liepin.java'; Baseline = 'baseline/src__main__java__com__getjobs__worker__liepin__Liepin.java' },
    @{ Relative = 'src/main/java/com/getjobs/worker/liepin/LiepinConfig.java'; Baseline = 'baseline/src__main__java__com__getjobs__worker__liepin__LiepinConfig.java' },
    @{ Relative = 'src/main/java/com/getjobs/worker/liepin/LiepinRateGuard.java'; Baseline = 'baseline/src__main__java__com__getjobs__worker__liepin__LiepinRateGuard.java' },
    @{ Relative = 'src/test/java/com/getjobs/worker/liepin/LiepinRateGuardTest.java'; Baseline = 'baseline/src__test__java__com__getjobs__worker__liepin__LiepinRateGuardTest.java' }
)

foreach ($file in $files) {
    $target = [System.IO.Path]::GetFullPath((Join-Path $resolvedRepo $file.Relative))
    $source = Join-Path $featureRoot $file.Baseline
    $rootPrefix = $resolvedRepo.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $target.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "目标路径越界: $target"
    }
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "基线文件缺失: $source"
    }
    $targetDir = Split-Path -Parent $target
    New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
    Copy-Item -LiteralPath $source -Destination $target -Force
}

foreach ($file in $files) {
    $target = [System.IO.Path]::GetFullPath((Join-Path $resolvedRepo $file.Relative))
    $source = Join-Path $featureRoot $file.Baseline
    $expected = (Get-FileHash -Algorithm SHA256 -LiteralPath $source).Hash
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash
    if ($expected -ne $actual) {
        throw "回滚哈希校验失败: $($file.Relative)"
    }
}

Write-Output 'ROLLBACK_OK'
