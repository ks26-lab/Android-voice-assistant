@echo off
setlocal enabledelayedexpansion

echo ================================================================
echo SAMSUNG PRISM THEME 3 - SLICE 1 FIXTURE WORKFLOW EXECUTION TESTS
echo ================================================================

set "SDK_DIR=D:\Softwares\Android\SDK"
if exist "%SDK_DIR%\platform-tools\adb.exe" (
    set "PATH=%SDK_DIR%\platform-tools;%SDK_DIR%\emulator;%PATH%"
)

echo.
echo [1] Running Gradle Unit & Slice 1 Fixture Tests...
call gradlew.bat testDebugUnitTest --tests com.chockXlate.teachablevoice.runtime.Slice1FixtureWorkflowExecutionTest

echo.
echo [2] Building Debug APK...
call gradlew.bat assembleDebug

echo.
echo [3] Installing onto Connected Pixel 6 Device/Emulator...
adb wait-for-device
adb install -r "app\build\outputs\apk\debug\app-debug.apk"

echo.
echo [4] Launching and Validating...
adb shell am force-stop com.chockXlate.teachablevoice
adb shell am start -n com.chockXlate.teachablevoice/.MainActivity
adb shell pidof com.chockXlate.teachablevoice

echo.
echo ================================================================
echo SLICE 1 VERIFICATION COMPLETED
echo ================================================================
pause
