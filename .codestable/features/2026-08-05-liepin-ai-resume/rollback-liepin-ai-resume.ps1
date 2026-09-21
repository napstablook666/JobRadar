param(
    [string]$BaseUrl = 'http://127.0.0.1:8888'
)

$ErrorActionPreference = 'Stop'
$api = "$BaseUrl/api/liepin"
$status = Invoke-RestMethod -Uri "$api/status" -TimeoutSec 30
if ($status.isRunning) {
    Invoke-RestMethod -Method Post -Uri "$api/stop" -TimeoutSec 30 | Out-Null
    Start-Sleep -Seconds 1
}

$body = @{
    id = 1
    keywords = '["放疗设备现场技术支持","放疗设备售后","放疗设备工程师","直线加速器技术支持","放疗设备应用支持","放疗应用工程师助理","放疗临床应用","医疗设备应用工程师","医疗器械应用工程师","医学影像设备工程师","医学影像临床应用","医疗设备技术支持","医疗器械技术支持","医疗设备售后","医疗器械售后","医疗设备安装调试","医疗设备维修工程师","医疗器械现场服务"]'
    city = '全国'
    salaryCode = '6$10'
    enableAi = 1
    maxPerRun = 30
    minDelaySeconds = 2
    maxDelaySeconds = 5
}

$result = Invoke-RestMethod -Method Put -Uri "$api/config" -ContentType 'application/json' -Body ($body | ConvertTo-Json -Compress) -TimeoutSec 30
if ($result.keywords -ne $body.keywords -or $result.salaryCode -ne $body.salaryCode) {
    throw '猎聘配置回滚校验失败'
}
Write-Output 'ROLLBACK_OK'
