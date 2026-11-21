@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0publish_curseforge.ps1" %*
echo.
pause
