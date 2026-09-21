param(
    [string]$Root = (Get-Location).Path,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

function Update-TextOnce {
    param(
        [string]$Path,
        [string]$Pattern,
        [string]$Replacement
    )

    $text = [System.IO.File]::ReadAllText($Path)
    $matches = [regex]::Matches($text, $Pattern)
    if ($matches.Count -ne 1) {
        throw "回滚定位数量异常: $Path -> $($matches.Count)"
    }
    if (-not $DryRun) {
        $updated = [regex]::Replace($text, $Pattern, [System.Text.RegularExpressions.MatchEvaluator]{ param($m) $Replacement }, 1)
        [System.IO.File]::WriteAllText($Path, $updated, [System.Text.UTF8Encoding]::new($false))
    }
}

function Update-TextAll {
    param(
        [string]$Path,
        [string]$Pattern,
        [string]$Replacement,
        [int]$ExpectedCount
    )

    $text = [System.IO.File]::ReadAllText($Path)
    $matches = [regex]::Matches($text, $Pattern)
    if ($matches.Count -ne $ExpectedCount) {
        throw "回滚定位数量异常: $Path -> $($matches.Count)，期望 $ExpectedCount"
    }
    if (-not $DryRun) {
        $updated = [regex]::Replace($text, $Pattern, [System.Text.RegularExpressions.MatchEvaluator]{ param($m) $Replacement })
        [System.IO.File]::WriteAllText($Path, $updated, [System.Text.UTF8Encoding]::new($false))
    }
}

$liepin = Join-Path $Root 'src\main\java\com\getjobs\worker\liepin\Liepin.java'
$test = Join-Path $Root 'src\test\java\com\getjobs\worker\liepin\LiepinAiGreetingDecisionTest.java'
$analysis = Join-Path $Root 'front\app\liepin\analysis\AnalysisContent.tsx'
foreach ($path in @($liepin, $test, $analysis)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "回滚目标缺失: $path" }
}

$previousAiBlock = @'
            String jd = null;
            AiGreetingResult aiResult = new AiGreetingResult(null, false);
            boolean autoSendPreset = false;
            if (config.isAiEnabled()) {
                jd = resolveJobDescription(apiEntity);
                if (jd != null && !jd.isBlank()) {
                    aiResult = generateAiGreeting(keyword, jobName, jd);
                    if (aiResult.rejected()) {
                        autoSendPreset = true;
                        info(String.format("AI判定岗位不匹配，改用平台默认沟通语【%s】【%s】", companyName, jobName));
                    }
                } else {
                    info(String.format("【%s】【%s】未读取到JD，准备使用猎聘预设语", companyName, jobName));
                }
            }
'@
Update-TextOnce $liepin '(?s)    private static final List<String> RELEVANT_DOMAIN_TERMS = List\.of\(.*?    private static final List<String> GENERIC_MANUFACTURING_TERMS = List\.of\(.*?\r?\n    \);\r?\n' ''
Update-TextOnce $liepin '(?s)\r?\n            String jd = null;\r?\n            AiGreetingResult aiResult = new AiGreetingResult\(null, false\);.*?(?=\r?\n            GreetingRequest greetingRequest = new GreetingRequest)' $previousAiBlock
Update-TextOnce $liepin '(?s)\r?\n    static boolean isUsableAiGreeting\(AiGreetingResult result\) \{.*?\r?\n    private record SendResult' "`r`n    private record SendResult"
Update-TextOnce $liepin '猎聘AI请求失败，将跳过当前岗位，不发送平台预设语' '猎聘AI请求失败，使用猎聘预设语'

Update-TextOnce $analysis '  // 默认先看真实已投递记录；取消“已投递”后仍可切换回完整岗位池。\r?\n  const \[statuses, setStatuses\] = useState<string\[\]>\(\["已投递"\]\)' '  const [statuses, setStatuses] = useState<string[]>([])'
Update-TextOnce $analysis '(?s)\r?\ntype ChartDataset = \{.*?declare global \{\r?\n  interface Window \{\r?\n    Chart\?: ChartConstructor\r?\n  \}\r?\n\}\r?\n' ''
Update-TextOnce $analysis 'useRef<ChartInstance \| null>' 'useRef<any | null>'
Update-TextOnce $analysis 'async function ensureChart\(\): Promise<ChartConstructor>' 'async function ensureChart(): Promise<any>'
Update-TextOnce $analysis 'if \(typeof window !== "undefined" && window\.Chart\) return window\.Chart' 'if (typeof window !== "undefined" && (window as any).Chart) return (window as any).Chart'
Update-TextAll $analysis 'existing\.addEventListener\("load", \(\) => window\.Chart \? resolve\(window\.Chart\) : reject\(new Error\("Chart\.js global missing"\)\)\)' 'existing.addEventListener("load", () => resolve((window as any).Chart))' 1
Update-TextOnce $analysis 'script\.addEventListener\("load", \(\) => window\.Chart \? resolve\(window\.Chart\) : reject\(new Error\("Chart\.js global missing"\)\)\)' 'script.addEventListener("load", () => resolve((window as any).Chart))'
Update-TextOnce $analysis 'const dataset: ChartDataset' 'const dataset: any'

Update-TextOnce $test 'falseResponseIsNotUsableForSending' 'falseResponseIsMarkedForAutomaticSkip'
Update-TextOnce $test 'blankResponseIsNotUsableForSending' 'blankResponseKeepsPresetFallback'
Update-TextAll $test '\r?\n        assertFalse\(Liepin\.isUsableAiGreeting\(result\)\);' '' 2
Update-TextOnce $test '\r?\n        assertTrue\(Liepin\.isUsableAiGreeting\(result\)\);' ''
Update-TextOnce $test '(?s)\r?\n    @Test\r?\n    void relevantMedicalDeviceJobIsAllowed\(\) \{.*?void missingJobDataIsRejected\(\) \{.*?\r?\n    \}\r?\n\}' "`r`n}"

if ($DryRun) {
    Write-Output 'ROLLBACK_CHECK_OK'
} else {
    Write-Output 'ROLLBACK_OK'
}
