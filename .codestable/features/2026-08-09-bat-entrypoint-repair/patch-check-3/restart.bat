@echo off
setlocal EnableExtensions
cd /d "%~dp0"
set "KEEP_OPEN=1"
if defined GETJOBS_NO_PAUSE set "KEEP_OPEN=0"
set "GETJOBS_NO_PAUSE=1"
echo [GetJobs] restarting from "%CD%"

call "%~dp0stop.bat" -Quiet
set "ERR=%ERRORLEVEL%"
if not "%ERR%"=="0" goto :report

call "%~dp0start.bat" %*
set "ERR=%ERRORLEVEL%"

:report
if "%ERR%"=="0" (
  echo [GetJobs] restart completed successfully.
) else (
  echo [GetJobs] restart failed, exit code %ERR%.
)
if "%KEEP_OPEN%"=="1" pause
endlocal & exit /b %ERR%

