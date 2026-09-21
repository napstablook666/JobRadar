param(
    [string]$BaseUrl = 'http://localhost:8888',
    [switch]$Preview,
    [switch]$Restore
)

$ErrorActionPreference = 'Stop'
$FeatureDir = $PSScriptRoot
$Backup = Join-Path $FeatureDir 'rollback-config-before.json'

function Get-State {
    $config = Invoke-RestMethod -Uri "$BaseUrl/api/liepin/config" -Method Get
    $status = Invoke-RestMethod -Uri "$BaseUrl/api/liepin/status" -Method Get
    return @{ Config = $config.config; Status = $status }
}

$state = Get-State
if ($Restore) {
    if (-not (Test-Path -LiteralPath $Backup)) { throw '缺少回滚前配置快照' }
    $payload = Get-Content -LiteralPath $Backup -Raw -Encoding UTF8
} else {
    $config = $state.Config
    $payloadObject = [ordered]@{
        id = $config.id
        keywords = $config.keywords
        city = $config.city
        salaryCode = $config.salaryCode
        enableAi = $config.enableAi
        autoAiDelivery = 0
        aiDeliveryMode = 'MANUAL'
        aiMinScore = $config.aiMinScore
        aiReviewMinScore = $config.aiReviewMinScore
        aiBatchSize = $config.aiBatchSize
        maxPerRun = $config.maxPerRun
        minDelaySeconds = $config.minDelaySeconds
        maxDelaySeconds = $config.maxDelaySeconds
    }
    $payload = $payloadObject | ConvertTo-Json -Depth 5
}

if ($Preview) {
    Write-Output 'PREVIEW_ONLY=1'
    Write-Output $payload
    exit 0
}
if ($state.Status.isRunning -and -not $Restore) {
    throw '投递任务仍在运行，先等待任务空闲'
}
if (-not $Restore) {
    $state.Config | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $Backup -Encoding UTF8
}
$updated = Invoke-RestMethod -Uri "$BaseUrl/api/liepin/config" -Method Put -ContentType 'application/json' -Body $payload
$check = (Get-State).Config
if ($Restore) {
    Write-Output ("RESTORE_MODE={0}; RESTORE_AUTO={1}" -f $check.aiDeliveryMode, $check.autoAiDelivery)
} else {
    if ($check.aiDeliveryMode -ne 'MANUAL' -or [int]$check.autoAiDelivery -ne 0) {
        throw '回滚后的投递模式回读不匹配'
    }
    Write-Output ("ROLLBACK_MODE={0}; ROLLBACK_AUTO={1}" -f $check.aiDeliveryMode, $check.autoAiDelivery)
}
exit 0
