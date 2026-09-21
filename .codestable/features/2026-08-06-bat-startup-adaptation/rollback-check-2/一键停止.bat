@echo off
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bin\stop-services.ps1" %*
set ERR=%ERRORLEVEL%
endlocal & exit /b %ERR%
