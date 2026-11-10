@echo off
rem Phone emulator for the companion app (second device next to the car). No sound. 2 cores froze Android 15 next to the game, so 4.
call "%~dp0..\tools\env.cmd"
rem The emulator resets its saved size when it exits, so set it on every start. launcher.py then moves the window to the second monitor (which makes it 1.25x larger here).
powershell -NoProfile -Command "$f='%ANDROID_AVD_HOME%\Phone.avd\emulator-user.ini'; if (Test-Path $f) { (Get-Content $f) -replace '^window.scale = .*','window.scale = 0.240000' | Set-Content $f }"
start "" emulator -avd Phone -port 5556 -memory 2048 -no-audio -cores 4
