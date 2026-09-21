@echo off
setlocal EnableExtensions
cd /d "%~dp0"
set "GETJOBS_NO_PAUSE=1"

call "%~dp0stop.bat" -Quiet
set "ERR=%ERRORLEVEL%"
if not "%ERR%"=="0" goto :done

call "%~dp0start.bat" %*
set "ERR=%ERRORLEVEL%"

:done
endlocal & exit /b %ERR%
