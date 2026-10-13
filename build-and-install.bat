@echo off
setlocal
cd /d "%~dp0"
call gradlew.bat assembleDebug
if errorlevel 1 exit /b %ERRORLEVEL%
if not exist "app\build\outputs\apk\debug\app-debug.apk" (
  echo APK was not produced.
  exit /b 1
)
echo.
echo Connected ADB devices:
adb devices -l
echo.
adb install -r "app\build\outputs\apk\debug\app-debug.apk"
if errorlevel 1 (
  echo Install failed. Read the full adb error above. If multiple devices are connected, use adb -s SERIAL install -r "app\build\outputs\apk\debug\app-debug.apk".
  exit /b 1
)
adb shell monkey -p com.example.wristpet 1
