@echo off
REM OpenHarness CLI wrapper (Windows PowerShell / CMD)
SETLOCAL

set SCRIPT_DIR=%~dp0
set JAR_NAME=openharness-app-0.1.0-SNAPSHOT.jar

REM 1. Check relative to this script
if exist "%SCRIPT_DIR%..\openharness-app\target\%JAR_NAME%" (
    set JAR=%SCRIPT_DIR%..\openharness-app\target\%JAR_NAME%
) else if exist "%SCRIPT_DIR%..\target\%JAR_NAME%" (
    set JAR=%SCRIPT_DIR%..\target\%JAR_NAME%
) else if exist "%USERPROFILE%\.openharness\lib\openharness.jar" (
    set JAR=%USERPROFILE%\.openharness\lib\openharness.jar
) else (
    echo Error: OpenHarness JAR not found
    echo Build with: mvn package -pl openharness-app -am -DskipTests
    exit /b 1
)

java -jar "%JAR%" %*
