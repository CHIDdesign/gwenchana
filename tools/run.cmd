@echo off
rem Run any command with the SSD toolchain, from the project root. e.g. tools\run.cmd gradlew test
call "%~dp0env.cmd"
cd /d "%PROJECT%"
%*
