$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$paths = @(
    'src/test/java/com/getjobs/worker/liepin/LiepinPageLifecycleTest.java',
    '.codestable/issues/2026-08-06-liepin-target-closed'
)
foreach ($relative in $paths) {
    $target = [IO.Path]::GetFullPath((Join-Path $root $relative))
    $rootWithSep = $root.TrimEnd('\') + '\'
    if (-not $target.StartsWith($rootWithSep, [StringComparison]::OrdinalIgnoreCase)) {
        throw "回滚路径越界: $target"
    }
    if (Test-Path -LiteralPath $target) {
        Remove-Item -LiteralPath $target -Recurse -Force
    }
}
Write-Output '已移除本次新增的测试与 CodeStable issue 产物。Java 源文件保留，需按审阅后的 diff 回滚。'
