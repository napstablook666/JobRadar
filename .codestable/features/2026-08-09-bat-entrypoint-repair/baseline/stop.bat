@echo off
setlocal EnableExtensions
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0bin\stop-services.ps1" %*
set "ERR=%ERRORLEVEL%"
endlocal & exit /b %ERR%
