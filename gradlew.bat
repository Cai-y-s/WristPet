@echo off
setlocal EnableExtensions
set "ROOT=%~dp0"
set "GRADLE_VERSION=8.14.2"
set "GRADLE_HOME_LOCAL=%ROOT%.gradle\gradle-%GRADLE_VERSION%"
set "GRADLE_EXE=%GRADLE_HOME_LOCAL%\bin\gradle.bat"

if not exist "%GRADLE_EXE%" (
  echo Gradle %GRADLE_VERSION% not found locally. Downloading Gradle distribution...
  if not exist "%ROOT%.gradle" mkdir "%ROOT%.gradle"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; $url='https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip'; $zip=Join-Path $env:TEMP 'wristpet-gradle-%GRADLE_VERSION%.zip'; Invoke-WebRequest -Uri $url -OutFile $zip; Expand-Archive -LiteralPath $zip -DestinationPath '%ROOT%.gradle' -Force; Remove-Item -LiteralPath $zip -Force"
  if errorlevel 1 (
    echo Failed to download Gradle. Check your network or open the folder in Android Studio.
    exit /b 1
  )
)

call "%GRADLE_EXE%" %*
exit /b %ERRORLEVEL%
