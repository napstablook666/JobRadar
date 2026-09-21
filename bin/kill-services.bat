@echo off
setlocal EnableExtensions
set "ROOT=%~dp0.."
cd /d "%ROOT%"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\bin\stop-services.ps1" %*
set "ERR=%ERRORLEVEL%"
endlocal & exit /b %ERR%
