@echo off
cd /d "%~dp0"
echo ======================================================
echo  PostFlight Enterprise - Automated QA Tests
 echo  Runs safely on a SEPARATE temporary data copy
 echo  Your existing PF-2103 and PF-REL-2126 are not touched
 echo ======================================================
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0RUN_ALL_POSTFLIGHT_TESTS.ps1"
if errorlevel 1 echo TEST RUN HAD ERRORS. Check the report if present.
echo.
echo Press any key to close this window.
pause >nul
