@echo off
rem Pleos emulator, cold boot (quick boot disables VHAL). 6 cores: its 3D driving view alone uses more than 1 core.
call "%~dp0..\tools\env.cmd"
rem The emulator resets its saved size when it exits, so set it on every start. launcher.py then moves the window to the second monitor (which makes it 1.25x larger here).
powershell -NoProfile -Command "$f='%ANDROID_AVD_HOME%\Pleos_Connect.avd\emulator-user.ini'; if (Test-Path $f) { (Get-Content $f) -replace '^window.scale = .*','window.scale = 0.414000' | Set-Content $f }"
start "" emulator -avd Pleos_Connect -no-snapshot-load -cores 6
