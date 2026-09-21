param(
    [switch]$Preview
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path

$entries = @(
    @{ Target = 'src\main\java\com\getjobs\worker\liepin\Liepin.java'; Baseline = 'Liepin.java.baseline'; CurrentHash = '269CA9C7C92419DD65725CCB5DD27935021123C93787C634E896CF143FB52307' },
    @{ Target = 'src\main\java\com\getjobs\application\service\AiService.java'; Baseline = 'AiService.java.baseline'; CurrentHash = 'E4D9CD58986A712BFCEE77866CB3C573178FB35099F5100B2B0AECF465ACBDF4' },
    @{ Target = 'src\main\java\com\getjobs\worker\service\LiepinJobService.java'; Baseline = 'LiepinJobService.java.baseline'; CurrentHash = '9CD694554CC127104DCB8DBD4A2DD960921FACC417CDE2A701FEA04B9AE2D618' },
    @{ Target = 'front\app\liepin\page.tsx'; Baseline = 'page.tsx.baseline'; CurrentHash = 'B8E1C1A42B7E6FA06E97D987307C05878D49835FB86117C53DCF72958AFF6EC3' },
    @{ Target = '进度白板.md'; Baseline = '进度白板.md.baseline'; CurrentHash = '7D5A2432D8EA0A43FF35E1CA73198E9EE43B4B64B5D7D106F5819009474A673F' }
)
$feature = Join-Path $root '.codestable\features\2026-08-07-liepin-batch-send-retry'
$newTest = Join-Path $root 'src\test\java\com\getjobs\worker\liepin\LiepinBatchDeliveryGuardTest.java'

function Get-RootPath([string]$relative) {
    $path = [System.IO.Path]::GetFullPath((Join-Path $root $relative))
    if (-not $path.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "路径超出工作区: $relative"
    }
    return $path
}

if ($Preview) {
    Write-Output 'PREVIEW_ONLY=1'
    foreach ($entry in $entries) {
        $target = Get-RootPath $entry.Target
        $hash = (Get-FileHash $target -Algorithm SHA256).Hash
        Write-Output ("{0} CURRENT_HASH={1} EXPECTED_HASH={2}" -f $entry.Target, $hash, $entry.CurrentHash)
    }
    $testHash = (Get-FileHash $newTest -Algorithm SHA256).Hash
    Write-Output ("{0} CURRENT_HASH={1} EXPECTED_HASH=E0389480F048DAABE536A0C0FB1CFFA3B48B1879B3D0D254E4901E5AA70A11C6" -f 'src\test\java\com\getjobs\worker\liepin\LiepinBatchDeliveryGuardTest.java', $testHash)
    exit 0
}

foreach ($entry in $entries) {
    $target = Get-RootPath $entry.Target
    $hash = (Get-FileHash $target -Algorithm SHA256).Hash
    if ($hash -ne $entry.CurrentHash) {
        throw "回滚保护触发，目标已被其他修改: $($entry.Target)"
    }
}
$testHash = (Get-FileHash $newTest -Algorithm SHA256).Hash
if ($testHash -ne 'E0389480F048DAABE536A0C0FB1CFFA3B48B1879B3D0D254E4901E5AA70A11C6') {
    throw '回滚保护触发，回归测试文件已被其他修改'
}

foreach ($entry in $entries) {
    Copy-Item -LiteralPath (Join-Path $feature $entry.Baseline) -Destination (Get-RootPath $entry.Target) -Force
}
Remove-Item -LiteralPath $newTest -Force
Write-Output 'ROLLBACK_APPLIED=1'
exit 0
