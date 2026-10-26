@echo off
setlocal
cd /d "%~dp0"
echo ========================================================
echo  SIMPLE TV LAUNCHER - COMPILACION AUTOMATICA
 echo  Java actual + Gradle 9.8.2 + Android SDK
 echo ========================================================
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0instalar-y-compilar.ps1"
set "RESULT=%ERRORLEVEL%"
echo.
if not "%RESULT%"=="0" (
 echo ERROR: No se ha podido completar la compilacion.
 echo Revisa el mensaje anterior. Comprueba internet y espacio libre.
) else (
 echo APK generado: app\build\outputs\apk\debug\app-debug.apk
)
echo.
pause
exit /b %RESULT%
