@echo off
setlocal EnableExtensions
cd /d "%~dp0"
set "PS_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%PS_EXE%" set "PS_EXE=powershell.exe"
echo [GetJobs] stopping from "%CD%"
"%PS_EXE%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0bin\stop-services.ps1" %*
set "ERR=%ERRORLEVEL%"
if not "%ERR%"=="0" (
  echo.
  echo [GetJobs] stop failed, exit code %ERR%.
  if not defined GETJOBS_NO_PAUSE pause
)
endlocal & exit /b %ERR%
