param(
    [string]$BaseUrl = "http://localhost:8888",
    [int]$RestoreMaxPerRun = 1
)

$ErrorActionPreference = "Stop"
$config = Invoke-RestMethod -Uri "$BaseUrl/api/liepin/config"
$c = $config.config
$body = [ordered]@{
    id = $c.id; keywords = $c.keywords; city = $c.city; salaryCode = $c.salaryCode
    enableAi = $c.enableAi; autoAiDelivery = $c.autoAiDelivery; aiDeliveryMode = $c.aiDeliveryMode
    aiMinScore = $c.aiMinScore; aiReviewMinScore = $c.aiReviewMinScore; aiBatchSize = $c.aiBatchSize
    aiTimeoutRetryEnabled = $c.aiTimeoutRetryEnabled; aiTimeoutMaxRetries = $c.aiTimeoutMaxRetries
    aiTimeoutRetryDelaySeconds = $c.aiTimeoutRetryDelaySeconds; maxPerRun = $RestoreMaxPerRun
    minDelaySeconds = $c.minDelaySeconds; maxDelaySeconds = $c.maxDelaySeconds
    searchMinDelaySeconds = $c.searchMinDelaySeconds; searchMaxDelaySeconds = $c.searchMaxDelaySeconds
    pageMinDelaySeconds = $c.pageMinDelaySeconds; pageMaxDelaySeconds = $c.pageMaxDelaySeconds
    detailMinDelaySeconds = $c.detailMinDelaySeconds; detailMaxDelaySeconds = $c.detailMaxDelaySeconds
    rateGuardBatchSize = $c.rateGuardBatchSize; batchCooldownMinSeconds = $c.batchCooldownMinSeconds
    batchCooldownMaxSeconds = $c.batchCooldownMaxSeconds
}
$saved = Invoke-RestMethod -Uri "$BaseUrl/api/liepin/config" -Method Put `
    -ContentType "application/json; charset=utf-8" -Body ($body | ConvertTo-Json -Depth 5)
if ($saved.maxPerRun -ne $RestoreMaxPerRun -or $saved.aiDeliveryMode -ne "BATCH_AUTO" -or $saved.autoAiDelivery -ne 1) {
    throw "rollback verification failed"
}
"rollback verified: maxPerRun=$($saved.maxPerRun); mode=$($saved.aiDeliveryMode); autoAiDelivery=$($saved.autoAiDelivery)"
