@echo off
title Periksa Paket TikTok Sony LIVE R17
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-R17-Fresh.ps1" -VerifyOnly
echo.
pause
