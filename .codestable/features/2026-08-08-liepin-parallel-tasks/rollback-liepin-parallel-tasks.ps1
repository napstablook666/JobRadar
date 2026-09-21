param(
    [switch]$Preview
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$feature = Join-Path $root '.codestable\features\2026-08-08-liepin-parallel-tasks'

$entries = @(
    @{ Target = 'front\app\liepin\analysis\AnalysisContent.tsx'; Baseline = 'baseline\front__app__liepin__analysis__AnalysisContent.tsx'; CurrentHash = '05E7700F8AC619181A1AD57FB6CEE4C0DFD48F535B4C6F5EBD2325516745FD45' },
    @{ Target = 'front\app\liepin\page.tsx'; Baseline = 'baseline\front__app__liepin__page.tsx'; CurrentHash = '0CF7364640FF412900621F04CF3584FFD1CB8C26809E942A9C654872A7372668' },
    @{ Target = 'src\main\java\com\getjobs\application\controller\LiepinController.java'; Baseline = 'baseline\src__main__java__com__getjobs__application__controller__LiepinController.java'; CurrentHash = '2ACBBD0495F6583DAE3EB72BE244C73792DE9E499438398F61C2B912CA574461' },
    @{ Target = 'src\main\java\com\getjobs\worker\liepin\Liepin.java'; Baseline = 'baseline\src__main__java__com__getjobs__worker__liepin__Liepin.java'; CurrentHash = 'B610F0DAD2EBD2FF66C200EC62E788F426F315B7B7592F701101498BEF4FC44E' },
    @{ Target = 'src\main\java\com\getjobs\worker\liepin\LiepinRateGuard.java'; Baseline = 'baseline\src__main__java__com__getjobs__worker__liepin__LiepinRateGuard.java'; CurrentHash = '5B4CEB31BAFA964DCA654FED76EEA69E3E364CA8EDDF7679018B1F5EB0ABA34B' },
    @{ Target = 'src\main\java\com\getjobs\worker\service\LiepinJobService.java'; Baseline = 'baseline\src__main__java__com__getjobs__worker__service__LiepinJobService.java'; CurrentHash = '37321C3C7D4DC2F3EF632790F6912E8FEA41A90F80B22F8A536E9C995E4AB718' },
    @{ Target = 'src\test\java\com\getjobs\application\controller\LiepinControllerPendingCleanupTest.java'; Baseline = 'baseline\src__test__java__com__getjobs__application__controller__LiepinControllerPendingCleanupTest.java'; CurrentHash = 'C33746E6B3E04DF7C9CDE2C5ED43A916651C86A9158B5DA20E36BE03885E3002' },
    @{ Target = 'src\test\java\com\getjobs\worker\liepin\LiepinRateGuardTest.java'; Baseline = 'baseline\src__test__java__com__getjobs__worker__liepin__LiepinRateGuardTest.java'; CurrentHash = 'A54B8CA224F1CB058D8F3F2311D7AFF1148D5769F077F1956268A4AEA500F145' },
    @{ Target = 'src\test\java\com\getjobs\worker\service\LiepinJobServiceDeliveryTest.java'; Baseline = 'baseline\src__test__java__com__getjobs__worker__service__LiepinJobServiceDeliveryTest.java'; CurrentHash = '28DC21B425F0AAD005C6380A4ECBE84A19D24372FDB6ACC0A331AF6A2B342DA4' },
    @{ Target = '进度白板.md'; Baseline = '进度白板.md.baseline'; CurrentHash = 'B7B11D4692AB1131908114D5F417BA1EB298F357CE28458A6A85120D2E2F4D59' }
)

function Get-RootPath([string]$relative) {
    $path = [System.IO.Path]::GetFullPath((Join-Path $root $relative))
    $rootWithSeparator = $root.TrimEnd('\') + '\'
    if (-not $path.StartsWith($rootWithSeparator, [System.StringComparison]::OrdinalIgnoreCase) -and $path -ne $root) {
        throw "路径超出工作区: $relative"
    }
    return $path
}

if ($Preview) {
    Write-Output 'PREVIEW_ONLY=1'
    foreach ($entry in $entries) {
        $target = Get-RootPath $entry.Target
        $hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
        Write-Output ("{0} CURRENT_HASH={1} EXPECTED_HASH={2}" -f $entry.Target, $hash, $entry.CurrentHash)
    }
    exit 0
}

foreach ($entry in $entries) {
    $target = Get-RootPath $entry.Target
    $hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
    if ($hash -ne $entry.CurrentHash) {
        throw "回滚保护触发，目标已被其他修改: $($entry.Target)"
    }
}

foreach ($entry in $entries) {
    $target = Get-RootPath $entry.Target
    $baseline = Join-Path $feature $entry.Baseline
    Copy-Item -LiteralPath $baseline -Destination $target -Force
}

Write-Output 'ROLLBACK_APPLIED=1'
exit 0
