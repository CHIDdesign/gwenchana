@echo off
rem Ask the Car: stops the bridge, emulators and OBS. Close BeamNG yourself.
cd /d "%~dp0game\bridge"
python launcher.py --stop
pause
