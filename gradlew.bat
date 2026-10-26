@echo off
setlocal
set "VER=9.8.2"
set "BASE=%~dp0.gradle-dist"
set "ZIP=%BASE%\gradle-%VER%-bin.zip"
set "GRADLE=%BASE%\gradle-%VER%\bin\gradle.bat"
if exist "%GRADLE%" goto run
if not exist "%BASE%" mkdir "%BASE%"
if not exist "%ZIP%" (
  echo Downloading Gradle %VER%...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing -Uri 'https://services.gradle.org/distributions/gradle-%VER%-bin.zip' -OutFile '%ZIP%'"
  if errorlevel 1 exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force -Path '%ZIP%' -DestinationPath '%BASE%'"
if errorlevel 1 exit /b 1
:run
call "%GRADLE%" %*
endlocal
