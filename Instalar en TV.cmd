@echo off
rem Instala el launcher en una TV por ADB inalambrico: pide IP, puerto y codigo y hace el resto.
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\instalar-tv.ps1"
