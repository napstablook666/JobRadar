$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$modifiedRoot = Join-Path $PSScriptRoot 'patch-input\modified'
$baselineRoot = Join-Path $PSScriptRoot 'patch-input\baseline'
$files = @(
    'src\main\java\com\getjobs\application\service\AiService.java',
    'src\main\java\com\getjobs\application\controller\AiConfigController.java',
    'src\main\java\com\getjobs\worker\boss\Boss.java',
    'src\main\java\com\getjobs\worker\job51\Job51.java',
    'src\main\java\com\getjobs\worker\liepin\Liepin.java',
    'src\test\java\com\getjobs\application\service\AiPromptTest.java'
)

foreach ($dir in @($modifiedRoot, $baselineRoot)) {
    if (Test-Path -LiteralPath $dir) {
        Remove-Item -LiteralPath $dir -Recurse -Force
    }
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
}

function Write-Utf8([string]$path, [string]$text) {
    [System.IO.File]::WriteAllText($path, $text, [System.Text.UTF8Encoding]::new($false))
}

foreach ($relative in $files) {
    $source = Join-Path $root $relative
    $modified = Join-Path $modifiedRoot $relative
    $baseline = Join-Path $baselineRoot $relative
    New-Item -ItemType Directory -Path (Split-Path $modified), (Split-Path $baseline) -Force | Out-Null
    Copy-Item -LiteralPath $source -Destination $modified
    $text = [System.IO.File]::ReadAllText($source)

    if ($relative -like '*AiService.java') {
        $text = [regex]::Replace($text, '(?s)\r?\n    /\*\* 统一校验可直接发送的 AI 打招呼语。 \*/.*?(?=\r?\n    /\*\* 渲染批量 AI 使用的命名模板)', '')
        $text = [regex]::Replace($text, '(?s)\r?\n    public static final String GREETING_PREFIX.*?(?=\r?\n    public static final String DEFAULT_SCREEN_PROMPT)', '')
    }
    elseif ($relative -like '*AiConfigController.java') {
        $replacement = @"
            response.put("success", true);
            response.put("data", reply);
"@
        $text = [regex]::Replace($text, '(?s)            AiService\.GreetingValidation validation = AiService\.validateGreeting\(reply, introduce\);.*?            response\.put\("data", validation\.message\(\)\);', $replacement, 1)
    }
    elseif ($relative -like '*Boss.java') {
        $text = [regex]::Replace($text, '(?m)^            AiService\.GreetingValidation validation = AiService\.validateGreeting\(result, introduce\);\r?\n            return validation\.usable\(\) \? validation\.message\(\) : config\.getSayHi\(\);', '            return result.toLowerCase().contains("false") ? config.getSayHi() : result;', 1)
    }
    elseif ($relative -like '*Job51.java') {
        $text = [regex]::Replace($text, '(?s)            AiService\.GreetingValidation validation = AiService\.validateGreeting\(classified\.message\(\), introduce\);\r?\n            return validation\.usable\(\)\r?\n                    \? new AiGreetingResult\(validation\.message\(\), false\)\r?\n                    : new AiGreetingResult\(null, false\);', '            return classified;')
    }
    elseif ($relative -like '*Liepin.java') {
        $text = $text.Replace('        return AiService.validateGreeting(message, introduce).usable();', '        return true;')
        $text = [regex]::Replace($text, '(?s)                    AiService\.GreetingValidation validation = AiService\.validateGreeting\(classified\.message\(\), introduce\);\r?\n                    return validation\.usable\(\)\r?\n                            \? new AiGreetingResult\(validation\.message\(\), false\)\r?\n                            : new AiGreetingResult\(null, false\);', '                    return classified;')
    }
    elseif ($relative -like '*AiPromptTest.java') {
        $text = [regex]::Replace($text, '(?s)\r?\n    @Test\r?\n    void acceptsGreetingWithTheRequiredPrefix\(\) \{.*?(?=\r?\n    @Test\r?\n)', '')
        $text = [regex]::Replace($text, '(?s)\r?\n    @Test\r?\n    void rejectsCandidateNameAndWrongPrefix\(\) \{.*?(?=\r?\n    @Test\r?\n)', '')
        $text = [regex]::Replace($text, '(?s)\r?\n    @Test\r?\n    void normalizesMarkdownAndPreservesFalseRejection\(\) \{.*?(?=\r?\n\})', '')
    }

    Write-Utf8 $baseline $text
}

$patchPath = Join-Path $PSScriptRoot 'ai-greeting-no-name.patch'
$relativeBase = (Resolve-Path $PSScriptRoot).Path.Substring($root.Length + 1).Replace('\', '/')
Push-Location $root
$diff = & git diff --no-index --src-prefix=a/ --dst-prefix=b/ -- "$relativeBase/patch-input/baseline/src" "$relativeBase/patch-input/modified/src"
$diffExit = $LASTEXITCODE
Pop-Location
$diffText = (($diff -join [Environment]::NewLine) + [Environment]::NewLine)
$diffText = $diffText.Replace(('a/' + $relativeBase + '/patch-input/baseline/src/'), 'a/src/')
$diffText = $diffText.Replace(('b/' + $relativeBase + '/patch-input/modified/src/'), 'b/src/')
Write-Utf8 $patchPath $diffText

Write-Output "PATCH_EXIT=$diffExit"
Write-Output "PATCH_PATH=$patchPath"
Write-Output "PATCH_BYTES=$((Get-Item -LiteralPath $patchPath).Length)"
Write-Output "PATCH_SHA256=$((Get-FileHash -Algorithm SHA256 -LiteralPath $patchPath).Hash)"
if ($diffExit -gt 1) { exit $diffExit }
exit 0
