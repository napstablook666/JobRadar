param(
    [switch]$OpenBrowser
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$env:GETJOBS_NO_PAUSE = "1"

$startArgs = @("-BrowserMode", "visible-login")
if ($OpenBrowser) {
    $startArgs += "-OpenBrowser"
} else {
    $startArgs += "-NoBrowser"
}

& (Join-Path $Root "bin\start-services.ps1") @startArgs
exit $LASTEXITCODE
