@echo off
rem Double-click on any PC: opens this project in the Android Studio stored on this SSD.
call "%~dp0..\tools\env.cmd"
start "" "%TOOLS%\android-studio\bin\studio64.exe" "%PROJECT%"
