param(
    [string]$BaseUrl = 'http://localhost:8888'
)

$ErrorActionPreference = 'Stop'
$api = "$($BaseUrl.TrimEnd('/'))/api/51job"

$status = Invoke-RestMethod -Uri "$api/status" -TimeoutSec 30
if ($status.isRunning) {
    Invoke-RestMethod -Method Post -Uri "$api/stop" -TimeoutSec 30 | Out-Null
    Start-Sleep -Seconds 1
}

$currentResponse = Invoke-RestMethod -Uri "$api/config" -TimeoutSec 30
$current = $currentResponse.config
$id = if ($null -ne $current.id) { [int64]$current.id } else { 1 }

$body = @{
    id = $id
    keywords = if ($null -ne $current.keywords) { $current.keywords } else { '[]' }
    jobArea = if ($null -ne $current.jobArea) { $current.jobArea } else { '[]' }
    salary = if ($null -ne $current.salary) { $current.salary } else { '[]' }
    enableAi = 0
    maxPerRun = 10
    minDelaySeconds = 5
    maxDelaySeconds = 10
}

$result = Invoke-RestMethod -Method Put -Uri "$api/config" -ContentType 'application/json' -Body ($body | ConvertTo-Json -Compress) -TimeoutSec 30
if ($result.enableAi -ne 0 -or $result.maxPerRun -ne 10 -or $result.minDelaySeconds -ne 5 -or $result.maxDelaySeconds -ne 10) {
    throw '51job AI 配置回滚校验失败'
}

Write-Output 'ROLLBACK_OK'
