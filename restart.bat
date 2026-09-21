@echo off
setlocal EnableExtensions
cd /d "%~dp0"
set "KEEP_OPEN=1"
if defined JOBRADAR_NO_PAUSE set "KEEP_OPEN=0"
set "JOBRADAR_NO_PAUSE=1"
echo [JobRadar] restarting from "%CD%"

call "%~dp0stop.bat" -Quiet
set "ERR=%ERRORLEVEL%"
if not "%ERR%"=="0" goto :report

call "%~dp0start.bat" %*
set "ERR=%ERRORLEVEL%"

:report
if "%ERR%"=="0" (
  echo [JobRadar] restart completed successfully.
) else (
  echo [JobRadar] restart failed, exit code %ERR%.
)
if "%KEEP_OPEN%"=="1" pause
endlocal & exit /b %ERR%
