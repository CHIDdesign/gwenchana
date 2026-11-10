@echo off
rem Ask the Car: start BeamNG first (free roam), then run this. Sets up OBS, emulator, app and the bridge.
cd /d "%~dp0game\bridge"
python launcher.py %*
pause
