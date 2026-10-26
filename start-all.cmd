@echo off
rem ===================================================================
rem  Personal AI Fitness System - one-click launcher (wrapper)
rem
rem  Why this wrapper: this machine's PowerShell execution policy
rem  forbids running .ps1 scripts directly (UnauthorizedAccess).
rem  -ExecutionPolicy Bypass applies to this single call only and
rem  does NOT change any system setting.
rem
rem  Usage:
rem    start-all.cmd                    start Redis + backend + frontend
rem    start-all.cmd -Stop              stop backend and frontend
rem    start-all.cmd -Stop -StopRedis   also stop the Redis container
rem ===================================================================
chcp 65001 >nul
setlocal
set "SCRIPT=%~dp0start-all.ps1"

if not exist "%SCRIPT%" (
    echo [ERROR] start-all.ps1 not found next to this file.
    endlocal & exit /b 1
)

rem Prefer PowerShell 7 (pwsh), fall back to any known location, then 5.1
set "PS="
where pwsh >nul 2>nul && set "PS=pwsh"
if not defined PS if exist "%ProgramFiles%\PowerShell\7\pwsh.exe" set "PS=%ProgramFiles%\PowerShell\7\pwsh.exe"
if not defined PS if exist "%LOCALAPPDATA%\Microsoft\WindowsApps\pwsh.exe" set "PS=%LOCALAPPDATA%\Microsoft\WindowsApps\pwsh.exe"
if not defined PS set "PS=powershell"

echo Using: %PS%
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*

set "CODE=%ERRORLEVEL%"
echo.
if not "%CODE%"=="0" echo [WARN] launcher exited with code %CODE%. See output above.
endlocal & exit /b %CODE%