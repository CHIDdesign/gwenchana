@echo off
rem Ask the Car: same as the normal start, but no phone emulator. Use a real Android phone on the same Wi-Fi.
cd /d "%~dp0game\bridge"
python launcher.py --no-phone %*
pause
