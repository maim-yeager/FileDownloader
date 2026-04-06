@echo off
REM =============================================================
REM FileDownloader - Automated Build Script for Windows
REM Usage: build.bat [debug|release]
REM =============================================================

set BUILD_TYPE=%1
if "%BUILD_TYPE%"=="" set BUILD_TYPE=debug

echo =============================================
echo   File Downloader - Auto Build Script
echo   Build type: %BUILD_TYPE%
echo =============================================

REM ── Check Java ───────────────────────────────────────────────
echo.
echo [1/4] Checking Java...
java -version >nul 2>&1
if errorlevel 1 (
  echo ERROR: Java not found.
  echo   Download JDK 17 from: https://adoptium.net
  pause
  exit /b 1
)
echo OK: Java found
java -version 2>&1 | findstr version

REM ── Check Android SDK ────────────────────────────────────────
echo.
echo [2/4] Checking Android SDK...
if "%ANDROID_HOME%"=="" (
  set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
)
if not exist "%ANDROID_HOME%" (
  echo ERROR: Android SDK not found at %ANDROID_HOME%
  echo   Install Android Studio: https://developer.android.com/studio
  echo   Or set ANDROID_HOME environment variable
  pause
  exit /b 1
)
echo OK: Android SDK at %ANDROID_HOME%

REM ── Check Gradle Wrapper ─────────────────────────────────────
echo.
echo [3/4] Building...

REM Build the project
if "%BUILD_TYPE%"=="release" (
  gradlew.bat assembleRelease
) else (
  gradlew.bat assembleDebug
)

if errorlevel 1 (
  echo.
  echo BUILD FAILED!
  echo.
  echo Try opening the project in Android Studio for detailed errors.
  pause
  exit /b 1
)

REM ── Show results ─────────────────────────────────────────────
echo.
echo [4/4] Build results:
echo.
echo BUILD SUCCESSFUL!
echo.

if "%BUILD_TYPE%"=="debug" (
  set APK_PATH=app\build\outputs\apk\debug\app-debug.apk
) else (
  set APK_PATH=app\build\outputs\apk\release\app-release-unsigned.apk
)

if exist "%APK_PATH%" (
  echo APK file: %CD%\%APK_PATH%
  copy "%APK_PATH%" "FileDownloader-%BUILD_TYPE%.apk" >nul
  echo Copied to: %CD%\FileDownloader-%BUILD_TYPE%.apk

  REM Try to install if device connected
  where adb >nul 2>&1
  if not errorlevel 1 (
    echo.
    set /p INSTALL=Install on connected device? (y/n): 
    if /i "%INSTALL%"=="y" (
      adb install -r "%APK_PATH%"
    )
  )
) else (
  echo APK not found at expected location.
)

echo.
pause
