@echo off
setlocal enabledelayedexpansion

echo ================================================================
echo SAMSUNG PRISM THEME 3 - AUTOMATED RECOVERY & VALIDATION PIPELINE
echo ================================================================

set "SDK_DIR=D:\Softwares\Android\SDK"
if exist "%SDK_DIR%\platform-tools\adb.exe" (
    set "PATH=%SDK_DIR%\platform-tools;%SDK_DIR%\emulator;%PATH%"
)

echo.
echo [PHASE 1] Resetting and Verifying ADB Server...
adb kill-server
adb start-server
adb devices -l

echo.
echo [PHASE 2] Checking Available AVDs...
emulator -list-avds

echo.
echo [PHASE 3] Checking Device Boot State...
echo Waiting for target device to come online...
adb wait-for-device
for /f "tokens=*" %%i in ('adb shell getprop sys.boot_completed') do set BOOT_COMPLETED=%%i
for /f "tokens=*" %%i in ('adb shell getprop dev.bootcomplete') do set DEV_BOOT=%%i
echo sys.boot_completed: %BOOT_COMPLETED%
echo dev.bootcomplete: %DEV_BOOT%

echo.
echo [PHASE 4] Verifying Target Device State...
adb devices -l

echo.
echo [PHASE 5] Installing APK Manually...
adb install -r "app\build\outputs\apk\debug\app-debug.apk"

echo.
echo [PHASE 6] Verifying Package Registration...
adb shell pm list packages | findstr chockXlate
adb shell dumpsys package com.chockXlate.teachablevoice | findstr /i "MainActivity"

echo.
echo [PHASE 7] Launching MainActivity...
adb shell am force-stop com.chockXlate.teachablevoice
adb logcat -c
adb shell am start -n com.chockXlate.teachablevoice/.MainActivity

echo.
echo [PHASE 8] Checking Logcat for Fatal Crashes...
adb logcat -d | findstr /i "FATAL EXCEPTION AndroidRuntime com.chockXlate.teachablevoice"

echo.
echo [PHASE 9] Verifying Foreground UI and Process PID...
adb shell dumpsys activity activities | findstr /i "mResumedActivity"
adb shell pidof com.chockXlate.teachablevoice

echo.
echo [PHASE 10] Checking and Enabling AccessibilityService...
adb shell settings get secure enabled_accessibility_services
adb shell settings put secure enabled_accessibility_services com.chockXlate.teachablevoice/com.chockXlate.teachablevoice.app.service.TeachableVoiceAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell settings get secure enabled_accessibility_services

echo.
echo [PHASE 11] Verifying Accessibility Runtime & Hierarchy Logs...
adb logcat -d | findstr /i "TeachableVoiceService TVA_CAPTURE AccessibilityUiDriver"

echo.
echo ================================================================
echo VALIDATION COMPLETE
echo ================================================================
pause
