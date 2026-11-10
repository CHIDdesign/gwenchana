@echo off
rem Point every dev tool at this folder (portable SSD). Works with any drive letter.
rem The project folder name contains a comma, which breaks QEMU (-drive file=...,) and the Kotlin compose plugin.
rem So all tools are reached through a comma-free junction at the SSD root: <drive>:\askthecar-tools -> <project>\tools
set "REALTOOLS=%~dp0"
set "REALTOOLS=%REALTOOLS:~0,-1%"
for %%I in ("%REALTOOLS%\..") do set "PROJECT=%%~fI"
set "TOOLS=%~d0\askthecar-tools"
rmdir "%TOOLS%" 2>nul
mklink /J "%TOOLS%" "%REALTOOLS%" >nul
rmdir "%~d0\askthecar-gradle" 2>nul
set "JAVA_HOME=%TOOLS%\jdk17"
set "ANDROID_HOME=%TOOLS%\sdk"
set "ANDROID_SDK_ROOT=%TOOLS%\sdk"
set "ANDROID_USER_HOME=%TOOLS%\android-user"
set "ANDROID_AVD_HOME=%TOOLS%\android-user\avd"
set "ANDROID_EMULATOR_HOME=%TOOLS%\android-user"
if not exist "%TOOLS%\gradle-home" mkdir "%TOOLS%\gradle-home"
set "GRADLE_USER_HOME=%TOOLS%\gradle-home"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%ANDROID_HOME%\emulator;%ANDROID_HOME%\cmdline-tools\latest\bin;%PATH%"
rem Rewrite sdk.dir every time so Gradle finds the SDK on whatever drive letter this is
set "SDKDIR=%ANDROID_HOME:\=\\%"
set "SDKDIR=%SDKDIR::=\:%"
> "%PROJECT%\local.properties" echo sdk.dir=%SDKDIR%
rem AVD pointer file holds an absolute path, so rewrite it for the current drive letter
> "%ANDROID_AVD_HOME%\Pleos_Connect.ini" echo avd.ini.encoding=UTF-8
>> "%ANDROID_AVD_HOME%\Pleos_Connect.ini" echo path=%ANDROID_AVD_HOME%\Pleos_Connect.avd
>> "%ANDROID_AVD_HOME%\Pleos_Connect.ini" echo path.rel=avd\Pleos_Connect.avd
>> "%ANDROID_AVD_HOME%\Pleos_Connect.ini" echo target=android-34
rem Phone emulator for the companion app (same reason: absolute path)
> "%ANDROID_AVD_HOME%\Phone.ini" echo avd.ini.encoding=UTF-8
>> "%ANDROID_AVD_HOME%\Phone.ini" echo path=%ANDROID_AVD_HOME%\Phone.avd
>> "%ANDROID_AVD_HOME%\Phone.ini" echo path.rel=avd\Phone.avd
>> "%ANDROID_AVD_HOME%\Phone.ini" echo target=android-35
