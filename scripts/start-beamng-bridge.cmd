@echo off
rem BeamNG (UDP 4445) to app test mode (TCP 8765) bridge. Leave this window open while driving.
cd /d "%~dp0..\game\bridge"
python beamng_bridge.py %*
pause
