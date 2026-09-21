param(
    [string]$BaseUrl = 'http://localhost:8888',
    [int]$Attempts = 20
)

$ErrorActionPreference = 'Stop'

$snapshotPath = Join-Path $PSScriptRoot 'ai-config-baseline.json'
if (-not (Test-Path -LiteralPath $snapshotPath)) {
    throw "配置快照不存在: $snapshotPath"
}

$baseline = Get-Content -Raw -LiteralPath $snapshotPath | ConvertFrom-Json
$data = $baseline.data
$body = @{
    introduce = [string]$data.introduce
    prompt = [string]$data.prompt
    screenPrompt = [string]$data.screenPrompt
    messagePrompt = [string]$data.messagePrompt
} | ConvertTo-Json -Depth 8

$api = "$($BaseUrl.TrimEnd('/'))/api/ai/config"
$result = Invoke-RestMethod $api `
    -Method Post -ContentType 'application/json; charset=utf-8' -Body $body
if (-not $result.success) {
    throw "配置回滚失败: $($result.message)"
}

function Normalize-Text([string]$value) {
    if ($null -eq $value) { return '' }
    return ($value -replace "`r`n?", "`n")
}
$verified = $false
$fields = @('introduce', 'prompt', 'screenPrompt', 'messagePrompt')
for ($attempt = 1; $attempt -le [Math]::Max(1, $Attempts); $attempt++) {
    $verify = Invoke-RestMethod $api -Method Get
    $fieldMatches = @($fields | ForEach-Object {
        (Normalize-Text $verify.data.$_) -eq (Normalize-Text $data.$_)
    })
    if ([bool]$verify.success -and ($fieldMatches -notcontains $false)) {
        $verified = $true
        break
    }
    Start-Sleep -Milliseconds 250
}
if (-not $verified) {
    throw '配置回滚回读校验失败'
}

Write-Output 'AI_CONFIG_ROLLBACK_OK'
