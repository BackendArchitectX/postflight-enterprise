@echo off
cd /d "%~dp0"
echo Starting PostFlight Enterprise local build...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1"
if errorlevel 1 (
 echo.
 echo Launch failed. Check that javac and java from JDK 17 or newer are installed.
 pause
)
