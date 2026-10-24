@echo off
rem Double-click launcher for the publish window.
rem Keep pythonw (no visible console). Do NOT use `2> log`: cmd fails with
rem "the system cannot find the path specified" when redirecting a GUI process.
rem Startup errors go to the window log; syntax errors: run `python tools\publish_gui.py`.

chcp 65001 >nul
setlocal
set "PYW="
for /f "delims=" %%P in ('where pythonw 2^>nul') do if not defined PYW set "PYW=%%P"
if not defined PYW (
  echo Python not found. Install Python 3 or run tools\publish_local.py
  pause
  exit /b 1
)
"%PYW%" "%~dp0publish_gui.py"
