@echo off
setlocal
REM ============================================================
REM  PREX LAUNCHER — one-click Windows EXE builder
REM
REM  Produces a real single-file launcher .exe with an embedded
REM  Java runtime (no Java install needed on the target PC),
REM  plus an optional .exe installer.
REM
REM  Requirements: Windows 10/11 + JDK 17 or newer on PATH
REM  (jpackage ships with every JDK 17+). Get one free from
REM  https://adoptium.net
REM ============================================================

set APP_NAME=PrexLauncher
set MAIN_JAR=prex-launcher.jar
set APP_VERSION=1.7.1
set VENDOR=Prex

REM files that sit next to this script
set SCRIPT_DIR=%~dp0
set INPUT_DIR=%SCRIPT_DIR%dist
set OUTPUT_DIR=%SCRIPT_DIR%out

if not exist "%INPUT_DIR%\%MAIN_JAR%" (
    echo [ERROR] %MAIN_JAR% not found in %INPUT_DIR%
    echo         Put the fat jar there and re-run this script.
    pause
    exit /b 1
)

where jpackage >nul 2>nul
if errorlevel 1 (
    echo [ERROR] jpackage not found. Install a full JDK 17+ and re-run.
    echo         https://adoptium.net
    pause
    exit /b 1
)

if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

REM ------- 1. standalone exe folder (portable: run PrexLauncher\PrexLauncher.exe) -------
echo.
echo [1/3] Building portable app image with embedded JRE ...
jpackage --type app-image ^
    --name %APP_NAME% ^
    --app-version %APP_VERSION% ^
    --vendor "%VENDOR%" ^
    --input "%INPUT_DIR%" ^
    --main-jar %MAIN_JAR% ^
    --main-class com.prex.launcher.Main ^
    --dest "%OUTPUT_DIR%" ^
    --icon "%SCRIPT_DIR%app-icon.ico"
if errorlevel 1 goto :error

REM ------- 2. single-file exe installer (download one file, double-click to install) -------
echo.
echo [2/3] Building EXE installer ...
jpackage --type exe ^
    --name %APP_NAME% ^
    --app-version %APP_VERSION% ^
    --vendor "%VENDOR%" ^
    --input "%INPUT_DIR%" ^
    --main-jar %MAIN_JAR% ^
    --main-class com.prex.launcher.Main ^
    --dest "%OUTPUT_DIR%" ^
    --icon "%SCRIPT_DIR%app-icon.ico" ^
    --win-menu --win-shortcut --win-dir-chooser
if errorlevel 1 goto :error

REM ------- 3. zip the portable folder (optional convenience) -------
echo.
echo [3/3] Zipping portable folder ...
if exist "%OUTPUT_DIR%\%APP_NAME%-portable.zip" del "%OUTPUT_DIR%\%APP_NAME%-portable.zip"
powershell -NoProfile -Command "Compress-Archive -Path '%OUTPUT_DIR%\%APP_NAME%' -DestinationPath '%OUTPUT_DIR%\%APP_NAME%-portable.zip' -Force"

echo.
echo ============================================================
echo   DONE! Files are in:  %OUTPUT_DIR%
echo     %APP_NAME%.exe        single-file installer (share this)
echo     %APP_NAME%-portable.zip   portable no-install version
echo ============================================================
pause
exit /b 0

:error
echo.
echo [ERROR] Build failed. Scroll up for the jpackage error message.
pause
exit /b 1
