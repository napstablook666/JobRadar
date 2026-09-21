param()

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$Feature = Join-Path $Root '.codestable\features\2026-08-06-liepin-ai-auto-delivery'

$restore = @{
    'src\main\java\com\getjobs\worker\liepin\Liepin.java' = 'baseline\src__main__java__com__getjobs__worker__liepin__Liepin.java'
    'src\main\java\com\getjobs\worker\liepin\LiepinConfig.java' = 'baseline\src__main__java__com__getjobs__worker__liepin__LiepinConfig.java'
    'src\main\java\com\getjobs\application\entity\LiepinConfigEntity.java' = 'baseline\src__main__java__com__getjobs__application__entity__LiepinConfigEntity.java'
    'src\main\java\com\getjobs\application\service\ConfigService.java' = 'baseline\src__main__java__com__getjobs__application__service__ConfigService.java'
    'src\main\java\com\getjobs\application\service\LiepinService.java' = 'baseline\src__main__java__com__getjobs__application__service__LiepinService.java'
    'front\app\liepin\page.tsx' = 'baseline\front__app__liepin__page.tsx'
    '进度白板.md' = 'baseline\进度白板.md'
}

foreach ($relative in $restore.Keys) {
    $source = Join-Path $Feature $restore[$relative]
    $target = Join-Path $Root $relative
    Copy-Item -LiteralPath $source -Destination $target -Force
}

$newFiles = @(
    'src\main\java\com\getjobs\worker\liepin\LiepinAiAssessment.java',
    'src\test\java\com\getjobs\worker\liepin\LiepinAiAssessmentTest.java',
    'src\test\java\com\getjobs\worker\liepin\LiepinConfigTest.java'
)
foreach ($relative in $newFiles) {
    $target = Join-Path $Root $relative
    if (Test-Path -LiteralPath $target) {
        Remove-Item -LiteralPath $target -Force
    }
}

Write-Output 'Rolled back code and progress board. Database compatibility columns were left in place.'
exit 0
