@echo off
setlocal EnableExtensions
cd /d "%~dp0"
set "GETJOBS_NO_PAUSE=1"
powershell.exe -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "%~dp0bin\start-services.ps1" %*
set "ERR=%ERRORLEVEL%"
endlocal & exit /b %ERR%
