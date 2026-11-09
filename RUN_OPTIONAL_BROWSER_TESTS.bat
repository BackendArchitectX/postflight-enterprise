@echo off
cd /d "%~dp0"
echo Optional PostFlight browser automation
where py >nul 2>&1
if %errorlevel%==0 (
    py -3 -m playwright --version >nul 2>&1
    if %errorlevel%==0 (
        py -3 "%~dp0qa\browser_tests.py"
        goto end
    )
)
where python >nul 2>&1
if %errorlevel%==0 (
    python -m playwright --version >nul 2>&1
    if %errorlevel%==0 (
        python "%~dp0qa\browser_tests.py"
        goto end
    )
)
echo.
echo Python Playwright is not installed. Browser tests were not run.
echo To enable this OPTIONAL suite, install Python 3 then run:
echo    python -m pip install playwright
echo    python -m playwright install chromium
:end
echo.
echo Press any key to close this window.
pause >nul
