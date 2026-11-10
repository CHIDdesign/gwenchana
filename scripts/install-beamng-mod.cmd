@echo off
rem Finds BeamNG on this PC (all Steam libraries), links game\beamng\mod\askthecar into its user folder and checks settings.
rem The bridge does the same check every time it starts (scripts/start-beamng-bridge.cmd), so this is optional.
cd /d "%~dp0..\game\bridge"
python -c "import beamng_bridge as b; print(b.setup_beamng())"
pause
