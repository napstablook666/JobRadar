@echo off
setlocal EnableExtensions
cd /d "%~dp0"
set "PS_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%PS_EXE%" set "PS_EXE=powershell.exe"
echo [JobRadar] starting from "%CD%"
"%PS_EXE%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0bin\start-services.ps1" %*
set "ERR=%ERRORLEVEL%"
if not "%ERR%"=="0" (
  echo.
  echo [JobRadar] start failed, exit code %ERR%.
  if not defined JOBRADAR_NO_PAUSE pause
)
endlocal & exit /b %ERR%
