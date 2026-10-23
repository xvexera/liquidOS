@echo off
rem TV Home installer - created by Wade (X: @wade9_)
rem Sets up ADB if needed, asks for the TV's IP, port and pairing code, then does the rest.
title TV Home installer - by Wade (@wade9_)
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\install-tv.ps1"
