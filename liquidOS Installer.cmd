@echo off
rem liquidOS installer - created by Wade (X: @wade9_)
rem Gets ADB ready, finds the TV, backs it up, then installs liquidOS and removes the factory bloat.
title liquidOS installer - by Wade (@wade9_)
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\liquidos-install.ps1"
