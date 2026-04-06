@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "PS1=%SCRIPT_DIR%deploy.ps1"

if exist "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" (
    "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %*
    exit /b %ERRORLEVEL%
)

where pwsh >nul 2>nul
if not errorlevel 1 (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %*
    exit /b %ERRORLEVEL%
)

echo PowerShell was not found. Install Windows PowerShell or PowerShell 7.
exit /b 1
